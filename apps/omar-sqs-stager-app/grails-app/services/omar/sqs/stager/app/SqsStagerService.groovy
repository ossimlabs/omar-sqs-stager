package omar.sqs.stager.app

import com.amazonaws.AmazonClientException
import com.amazonaws.auth.AWSCredentials
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain
import com.amazonaws.services.sqs.AmazonSQSClient
import com.amazonaws.services.sqs.model.DeleteMessageBatchRequest
import com.amazonaws.services.sqs.model.DeleteMessageBatchRequestEntry
import com.amazonaws.services.sqs.model.ReceiveMessageRequest
import groovy.time.TimeCategory
import groovy.time.TimeDuration
import joms.oms.DataInfo
import joms.oms.ImageStager
import omar.avro.AvroMessageUtils
import omar.avro.AvroService
import omar.avro.HttpUtils
import omar.avro.OmarAvroUtils
import omar.core.HttpStatus
import omar.core.DateUtil
import org.apache.commons.codec.digest.DigestUtils
import org.apache.http.HttpResponse
import org.apache.http.client.HttpClient
import org.apache.http.client.methods.HttpPost
import org.apache.http.entity.StringEntity
import org.apache.http.impl.client.DefaultHttpClient
import groovy.util.XmlSlurper
import groovy.json.JsonBuilder
import com.amazonaws.services.sqs.model.QueueAttributeName
import com.amazonaws.services.sqs.model.GetQueueAttributesRequest
import com.amazonaws.services.sqs.model.GetQueueAttributesResult


class SqsStagerService
{
   AvroService avroService
   AmazonSQSClient sqs
   def grailsApplication

    static Boolean checkMd5(String messageBodyMd5, String message)
    {
        String md5Check = DigestUtils.md5Hex(message);

        md5Check == messageBodyMd5

    }

    private AWSCredentials createCredentials()
    {
        AWSCredentials credentials = null;
        try
        {
            credentials = new DefaultAWSCredentialsProviderChain().credentials
        } catch (Exception e)
        {
            throw new AmazonClientException(
                    "Cannot load the credentials from the DefaultAWSCredentialsProviderChain. ",
                    e);
        }

        credentials
    }

    synchronized def getSqs()
    {
        if (!sqs) sqs = new AmazonSQSClient()//createCredentials())
        sqs
    }

    def postMessage(String url, String message)
    {
        def result = [status: 200, message: ""]
        try
        {
            HttpPost post = new HttpPost(url);
            post.addHeader("Content-Type", "application/json");
            StringEntity entity = new StringEntity(message);
            post.setEntity(entity);
            HttpClient client = new DefaultHttpClient();

            HttpResponse response = client.execute(post);

            /* other metrics information will be available in stager/avro */

            if (response)
            {
                result.message = response?.statusLine
                result.status = response.statusLine?.statusCode
            }
        }
        catch (e)
        {
            log.debug "${e}"
            result.status = 400
            result.message = e.toString()
        }

        result
    }

    def deleteMessages(String queue, def messages)
    {
        def sqs = getSqs()
        def deleteList = []
        Integer entryId = 1
        messages.each { message ->
            deleteList << new DeleteMessageBatchRequestEntry(entryId.toString(), message.receiptHandle)
            ++entryId
        }

        if (deleteList)
        {
            sqs.deleteMessageBatch(
                    new DeleteMessageBatchRequest(queue,
                            deleteList as List<DeleteMessageBatchRequestEntry>)
            )
        }
    }

    void getRemainingMessages()
    {
        AmazonSQSClient sqs = getSqs()
        def config = SqsUtils.sqsConfig
        HashMap messageRemainingInfo = [approximateNumberOfMessages: 0,
                     approximateNumberOfMessagesDelayed: 0,
                     approximateNumberOfMessagesNotVisible: 0
                     ]
        try
        {
            GetQueueAttributesResult sqsQueueAttributes = sqs.getQueueAttributes(
                new GetQueueAttributesRequest()
                .withQueueUrl(config.reader.queue)
                .withAttributeNames(QueueAttributeName.ApproximateNumberOfMessages, QueueAttributeName.ApproximateNumberOfMessagesDelayed, QueueAttributeName.ApproximateNumberOfMessagesNotVisible))

            messageRemainingInfo.approximateNumberOfMessages = sqsQueueAttributes.getAttributes().ApproximateNumberOfMessages
            messageRemainingInfo.approximateNumberOfMessagesDelayed = sqsQueueAttributes.getAttributes().ApproximateNumberOfMessagesDelayed
            messageRemainingInfo.approximateNumberOfMessagesNotVisible = sqsQueueAttributes.getAttributes().ApproximateNumberOfMessagesNotVisible

            log.info new JsonBuilder(messageRemainingInfo).toString()
        }
        catch (e)
        {
            log.error("ERROR: Unable to get queue atrributes.")
        }
    }

    def receiveMessages()
    {
        log.trace "receiveMessages: Entered........"
        def config = SqsUtils.sqsConfig

        def messages
        try
        {
            def sqs = getSqs()
            ReceiveMessageRequest receiveMessageRequest =
                    new ReceiveMessageRequest()
                            .withQueueUrl(config.reader.queue)
                            .withWaitTimeSeconds(config.reader.waitTimeSeconds)
                            .withMaxNumberOfMessages(config.reader.maxNumberOfMessages)
            messages = sqs.receiveMessage(receiveMessageRequest).messages
        }
        catch (e)
        {
            log.error("ERROR: Unable to receive message for queue: ${config.reader.queue}\n${e.toString()}")
        }

        getRemainingMessages()
        log.trace "receiveMessages: Leaving........"

        messages
    }

    def parseMessage(def message)
    {

        def jsonObj

        try
        {
            jsonObj = avroService.convertMessageToJsonWithSubField(message)

        }
        catch (Exception e)
        {
            jsonObj = null
            log.error e
        }

        return jsonObj
    }

    /**
     * Verifies if A 'term'  is on the image download black list
     * Example abstract: SICD 123456789
     *
     * @param term
     * @return matches
     */
    Boolean checkDownloadBlackList(String term )
    {

        boolean matches

        if(term == null){
            log.info ("metadataField is null!")
            return matches = false
        }
        log.info "Checking if ${term} against black list."

        /**
         * The string of black list files passed in from the configuration .yml
         */
        String [] blackListFiles = "${OmarAvroUtils?.avroConfig?.download?.blackList?.excludedTerms}".split(",")

        matches = blackListFiles.any{
            term.trim().toLowerCase().contains(it.trim().toLowerCase())
        }

        if(matches){
            log.info("${term} is on the black list.")
        }
        else {
            log.info "${term} is not on the black list."
            log.info("-"*75)
        }

        return matches
    }

    HashMap downloadFile(def message)
    {
        HashMap result = [status       : HttpStatus.OK,
                          message      : "",
                          requestMethod: "downloadFile",
                          source       : "",
                          destination  : "",
                          startTime    : new Date(),
                          endTime      : new Date(),
                          receiveDate  : null,
                          duration     : 0]
        def jsonObj = message

        try
        {
            if (jsonObj instanceof String)
            {
                jsonObj = parseMessage(message)
            }

            /**
             * Checks if the black list has been enabled in the configs
              */
            if(OmarAvroUtils?.avroConfig?.download?.blackList?.enabled) {
                log.info("-"*75)
                log.info ("Using the '${OmarAvroUtils?.avroConfig?.download?.blackList?.metadataField}' avro metadata field for black list")

                /**
                 * Use the associated avro metadata field passed in from the config
                 * to verify if the image is on the black list
                 */
                if (checkDownloadBlackList(jsonObj?."${OmarAvroUtils?.avroConfig?.download?.blackList?.metadataField}"))
                {
                    /**
                     * Uses the blackList.testMode configuration parameter to allow for a "Dry Run" to verify whether
                     * or not the excludedTerms are working as desired.  Test mode will still allow the image to be downloaded
                     * as normal, but will log if it was found on the black list.
                     */
                    if(OmarAvroUtils?.avroConfig?.download?.blackList?.testMode) {
                        log.info "BLACK LIST TEST MODE: Confirming that " + jsonObj?."${OmarAvroUtils?.avroConfig?.download?.blackList?.metadataField}" +
                                 " *is* on the black list, and would not be downloaded."
                        log.info("-"*75)
                    } else {
                        result.status = HttpStatus.METHOD_NOT_ALLOWED
                        result.message = "Image type not allowed to be downloaded (Black listed)"
                        result.endTime = new Date()
                        log.info('Image was on the black list, and was not downloaded\n')
                        log.info("-"*75)
                        return result
                    }
                }
            }

            String sourceURI = jsonObj?."${OmarAvroUtils.avroConfig.sourceUriField}" ?: ""
            //println "This is the source URI: ${sourceURI}"
            if (sourceURI)
            {

                String prefixPath = "${OmarAvroUtils.avroConfig.download.directory}"
                File fullPathLocation = avroService.getFullPathFromMessage(jsonObj)

                // Add extension to path in order to avoid image conflicts when only the extension is different.
                String fileExtension = fullPathLocation.path.substring(
                        fullPathLocation.path.lastIndexOf('.') + 1,
                        fullPathLocation.path.length()
                )
                fullPathLocation = new File("${fullPathLocation.parent}/${fileExtension}/${fullPathLocation.name}")

                File testPath = fullPathLocation?.parentFile
                Long fileSize = 0
                result.source = sourceURI
                HashMap tryToCreateDirectoryConfig = [
                        numberOfAttempts: OmarAvroUtils.avroConfig.createDirectoryRetry.toInteger(),
                        sleepInMillis   : OmarAvroUtils.avroConfig.createDirectoryRetryWaitInMillis.toInteger()
                ]
                result.destination = fullPathLocation.toString()
                if (!fullPathLocation.exists())
                {
                    if (AvroMessageUtils.tryToCreateDirectory(testPath, tryToCreateDirectoryConfig))
                    {
                        String commandString = OmarAvroUtils.avroConfig.download?.command
                        //println "COMMAND STRING === ${commandString}"

                        if (!commandString)
                        {
                            HttpUtils.downloadURI(fullPathLocation.toString(), sourceURI)
                        }
                        else
                        {
                            HttpUtils.downloadURIShell(commandString, result.destination?.toString(), sourceURI)
                        }
                        result.fileSize = fullPathLocation.size()
                        result.message = "Downloaded file to ${fullPathLocation}"
                    }
                    else
                    {
                        result.status = HttpStatus.NOT_FOUND
                        result.message = "Unable to create directory ${testPath}"
                    }
                }
                else
                {
                    result.status = HttpStatus.FOUND
                    result.message = "${fullPathLocation} already exists and will not be downloaded again"
                    result.fileSize = fullPathLocation.size()
                }
            }
            else
            {
                result.status = HttpStatus.NOT_FOUND
                result.message = "No source URI was found for download"
            }

        }
        catch (e)
        {
            result.status = HttpStatus.NOT_FOUND
            result.message = e.toString()
            e.printStackTrace()
        }

        result.endTime = new Date()

        result.duration = (result.endTime.time - result.startTime.time)

        // Include acquisition date in result so SqsStagerJob can use it.
        Date acquisitionDate = AvroMessageUtils.parseObservationDate(jsonObj)//DateUtil.parseDate(jsonObj?."${OmarAvroUtils.avroConfig.dateField}")
        result.acquisitionDate = acquisitionDate

        return result
    }

    static HashMap stageFileJni(HashMap params)
    {
        def result = [status       : HttpStatus.OK,
                      requestMethod: "stageFileJni",
                      message      : "",
                      startTime    : new Date(),
                      endTime      : null,
                      duration     : 0]
        ImageStager imageStager
        Boolean deleteStager = true
        if(params.imageStager)
        {
            imageStager = params.imageStager
            deleteStager = false
        }
        else
        {
            imageStager = new ImageStager()
        }
        String filename = params.filename

        try
        {
            if (imageStager.open(filename, params.failIfNoGeom?:false))
            {
                URI uri = new URI(filename)

                String scheme = uri.scheme
                if (!scheme) scheme = "file"
                if (scheme != "file")
                {
                    params.buildHistograms = false
                    params.buildOverviews = false
                }

                def oms = new XmlSlurper().parseText(DataInfo.readInfo(filename))
                def entryImageRepresentations = oms.dataSets.RasterDataSet.rasterEntries.RasterEntry.inject([:]) { imageRepresentations, entry ->
                    imageRepresentations[entry.entryId.toInteger()] = entry.metadata.imageRepresentation.toString(); imageRepresentations
                }

                Integer nEntries = imageStager.getNumberOfEntries()
                Integer idx = 0
                for(idx = 0; (idx < nEntries)&&!(imageStager?.isCancelled()); ++idx)
                {
                    if (idx == 0 || !(entryImageRepresentations[idx]?.equalsIgnoreCase("NODISPLY")?:false))
                    {
                        Boolean buildHistogramsWithR0 = params.buildHistogramsWithR0 != null ? params.buildHistogramsWithR0.toBoolean() : false
                        Boolean buildHistograms = params.buildHistograms != null ? params.buildHistograms.toBoolean() : false
                        Boolean buildOverviews = params.buildOverviews != null ? params.buildOverviews.toBoolean() : false
                        Boolean useFastHistogramStaging = params.useFastHistogramStaging != null ? params.useFastHistogramStaging.toBoolean() : false
                        Boolean buildThumbnails = params.buildThumbnails != null ? params.buildThumbnails.toBoolean() : true
                        Integer thumbnailSize = params.thumbnailSize != null ? params.thumbnailSize : 256
                        String thumbnailType = params.thumbnailType != null ? params.thumbnailType : "png"
                        String thumbnailStretchType = params.thumbnailStretchType != null ? params.thumbnailStretchType : "auto-minmax"
                        if(imageStager.setEntry(idx))
                        {
                            imageStager.setDefaults()
                            if(imageStager.hasProjection())
                            {
                                imageStager.setThumbnailStagingFlag( buildThumbnails, thumbnailSize )
                                imageStager.setThumbnailType( thumbnailType )
                                imageStager.setThumbnailStretchType( thumbnailStretchType )

                                imageStager.setHistogramStagingFlag(buildHistograms)
                                imageStager.setOverviewStagingFlag(buildOverviews)
                                if (params.overviewCompressionType != null) imageStager.setCompressionType(params.overviewCompressionType)
                                if (params.overviewType != null) imageStager.setOverviewType(params.overviewType)
                                if (params.useFastHistogramStaging != null) imageStager.setUseFastHistogramStagingFlag(useFastHistogramStaging)
                                imageStager.setQuietFlag(true)
                                // we only need to test the Build R0 if we already have overviews.  Images
                                // such as J2K have embedded overviews so we will need to build from R0 for those
                                // or our histograms do not come out properly
                                if(imageStager.hasOverviews())
                                {
                                    if (buildHistograms && buildOverviews
                                            && buildHistogramsWithR0)
                                    {
                                        imageStager.setHistogramStagingFlag(false)
                                        imageStager.stage()

                                        imageStager.setHistogramStagingFlag(true)
                                        imageStager.setOverviewStagingFlag(false)
                                    }
                                }

                                imageStager.stage()
                            }
                        }
                    }
                }
                if(imageStager?.isCancelled())
                {
                    result.message = "Staging cancelled for ${filename}"
                    result.status = HttpStatus.BAD_REQUEST
                }
                else
                {
                    result.message = "Staged file ${filename}"
                }
                if(deleteStager)
                {
                    imageStager.delete()
                }
                imageStager = null
            }
            else
            {
                result.status = HttpStatus.UNSUPPORTED_MEDIA_TYPE
                result.message = "Unable to open file ${params.filename}"
            }

            result.endTime = new Date()

            result.duration = (result.endTime.time - result.startTime.time)
        }
        catch (e)
        {
            //result.status = HttpStatus.UNSUPPORTED_MEDIA_TYPE
            result.message = "Unable to process file ${params.filename} with ERROR: ${e}"
        }
        finally
        {
            if(deleteStager)
            {
                imageStager?.delete()
            }
            imageStager = null
        }
        //println result
        result
    }

    static HashMap getDataInfo(String filename, Integer entryId = null)
    {
        HashMap result = [status       : HttpStatus.OK,
                          message      : "",
                          requestMethod: "getDataInfo",
                          startTime    : new Date(),
                          endTime      : null,
                          duration     : 0,
                          xml          : ""]
        DataInfo dataInfo = new DataInfo();
        String xml

        try
        {
            if (dataInfo.open(filename))
            {
                if (entryId != null)
                {
                    xml = dataInfo.getImageInfo(entryId as Integer);
                }
                else
                {
                    xml = dataInfo.info
                }
            }
            else
            {
                result.status = HttpStatus.UNSUPPORTED_MEDIA_TYPE
                result.message = "Could not open file: ${filename}"
            }
        }
        catch (e)
        {
            result.status = HttpStatus.NOT_FOUND
            result.message = e.toString()
        }
        finally
        {
            dataInfo.close()
            dataInfo.delete();
            dataInfo = null;
            result.xml = xml
            result.endTime = new Date()
            result.duration = (result.endTime.time - result.startTime.time)
        }

        result
    }

    HashMap postXml(String url, String xml)
    {
        def result = [status       : HttpStatus.OK,
                      message      : "",
                      requestMethod: "postXml",
                      startTime    : new Date(),
                      endTime      : null,
                      duration     : 0]
        try
        {
            def config = SqsUtils.sqsConfig
            HashMap postResult = HttpUtils.postData(url,
                    xml,
                    "application/xml")

            result.status = postResult.status
            result.message = postResult.message
            result.endTime = new Date()
            result.duration = (result.endTime.time - result.startTime.time)
        }
        catch (e)
        {
            result.status = HttpStatus.NOT_FOUND
            result.message = e.toString()
        }

        result
    }
}
