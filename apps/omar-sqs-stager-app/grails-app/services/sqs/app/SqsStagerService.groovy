package sqs.app

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

class SqsStagerService
{
   def avroService
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

//         messages = sqs.receiveMessage(config.reader.queue).messages

        }
        catch (e)
        {
            log.error("ERROR: Unable to receive message for queue: ${config.reader.queue}\n${e.toString()}")
        }
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
        catch (e)
        {
            jsonObj = null
            log.error e
        }

        jsonObj
    }

    HashMap downloadFile(def message)
    {
        HashMap result = [status       : HttpStatus.OK,
                          message      : "",
                          requestMethod: "downloadFile",
                          source       : "",
                          destination  : "",
                          startTime    : new Date(),
                          endTime      : null,
                          duration     : 0]
        def jsonObj = message
        String location

        try
        {
            if (jsonObj instanceof String)
            {
                jsonObj = parseMessage(message)
            }

            String sourceURI = jsonObj?."${OmarAvroUtils.avroConfig.sourceUriField}" ?: ""
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

            if (imageStager.open(filename))
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
                (0..<nEntries).each
                    {
                        if (it == 0 || !(entryImageRepresentations[it]?.equalsIgnoreCase("NODISPLY")?:false))
                        {
                            Boolean buildHistogramsWithR0 = params.buildHistogramsWithR0 != null ? params.buildHistogramsWithR0.toBoolean() : false
                            Boolean buildHistograms = params.buildHistograms != null ? params.buildHistograms.toBoolean() : false
                            Boolean buildOverviews = params.buildOverviews != null ? params.buildOverviews.toBoolean() : false
                            Boolean useFastHistogramStaging = params.useFastHistogramStaging != null ? params.useFastHistogramStaging.toBoolean() : false
                            Boolean buildThumbnails = params.buildThumbnails != null ? params.buildThumbnails.toBoolean() : true
                            Integer thumbnailSize = params.thumbnailSize != null ? params.thumbnailSize : 256
                            String thumbnailType = params.thumbnailType != null ? params.thumbnailType : "png"
                            String thumbnailStretchType = params.thumbnailStretchType != null ? params.thumbnailStretchType : "auto-minmax"
                            imageStager.setEntry(it)
                            imageStager.setDefaults()

                            imageStager.setThumbnailStagingFlag( buildThumbnails, thumbnailSize )
                            imageStager.setThumbnailType( thumbnailType )
                            imageStager.setThumbnailStretchType( thumbnailStretchType )

                            imageStager.setHistogramStagingFlag(buildHistograms)
                            imageStager.setOverviewStagingFlag(buildOverviews)
                            if (params.overviewCompressionType != null) imageStager.setCompressionType(params.overviewCompressionType)
                            if (params.overviewType != null) imageStager.setOverviewType(params.overviewType)
                            if (params.useFastHistogramStaging != null) imageStager.setUseFastHistogramStagingFlag(useFastHistogramStaging)
                            imageStager.setQuietFlag(true)

                            if (buildHistograms && buildOverviews
                                    && imageStager.hasOverviews() && buildHistogramsWithR0)
                            {

                                imageStager.setHistogramStagingFlag(false)
                                imageStager.stage()

                                imageStager.setHistogramStagingFlag(true)
                                imageStager.setOverviewStagingFlag(false)
                            }

                            imageStager.stage()
                        }
                    }
                result.message = "Staged file ${filename}"
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
