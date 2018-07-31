package sqs.app
import omar.core.HttpStatus
import groovy.util.logging.Slf4j
import groovy.json.JsonBuilder
import groovy.json.JsonSlurper
import groovy.time.TimeCategory
import groovy.time.TimeDuration
import omar.core.DateUtil
import omar.avro.HttpUtils
import omar.core.HttpStatus
import omar.avro.OmarAvroUtils
import groovy.transform.Synchronized
import joms.oms.ImageStager
import java.time.Duration

@Slf4j
class SqsStagerJobUtil
{
   String id
   Boolean cancelled = false
   HashMap messageInfo



   private def sqsStagerService
   private def sqsStagerJobService
   private def avroService
   private def rasterDataSetService
   private ImageStager imageStager
   private Object cancelledLock    = new Object()
   private static String JOB_CANCELLED_MESSAGE = "Job was cancelled"
   @Synchronized('cancelledLock')
   Boolean isCancelled()
   {
      cancelled
   }

   @Synchronized('cancelledLock')
   void cancel()
   {
      cancelled = true
      // for now I can only request a staging cancelation
      cancelStaging();
      // issue a 400 log HttpStatus.BAD_REQUEST
   }
   private void cancelStaging()
   {
      imageStager?.cancel()
   }
   private void cleanupCurrentMessage()
   {
      try{
         File file = new File(messageInfo.filename)

         if(file.exists())
         {
            String parent = file.parent
            String fileOnly = file.name.lastIndexOf('.').with {it != -1 ? file.name[0..<it] : file.name}
            if(parent && fileOnly)
            {
               def filenames = new FileNameByRegexFinder().getFileNames(parent,"${fileOnly}*")
               log.info "Cleaning filenames: ${filenames}"
               filenames.each{tempFile->
                  new File(tempFile).delete()
               }
            }
         }
      }
      catch(e)
      {
         log.error e.toString()
      }
   }

   void execute()
   {
      def config = SqsUtils.sqsConfig
      def messages
      // do some validation
      // if these are not set then let's not pop any messages off and just
      // log the error and return
      //
      Boolean okToProceed = true
      Boolean deleteMessageIfNoError = config.reader.deleteMessageIfNoError?:false
      Boolean needToCleanup = false
      if(!config?.reader?.queue)
      {
      // need to log error
         okToProceed = false
      }
      if(okToProceed)
      {
         String timestampName = config.reader.timestampName?:""

         while((!isCancelled())&&(messages = sqsStagerService?.receiveMessages()))
         {
           // mark the job as doing work
            sqsStagerJobService.jobStarted(this)

            messages?.each{message->
               Boolean okToDelete = true
               resetMessageInfo()
               Date startTimeDate = new Date()
               try{
                  messageInfo.messageId = message?.messageId
                  def json = new JsonSlurper().parseText(message?.body?:"")
                  def sqsTimestampDate
                  if (json?."${timestampName}")
                  {
                     sqsTimestampDate = DateUtil.parseDate(json."${timestampName}" as String)
                  }
                  else
                  {
                     sqsTimestampDate = startTimeDate
                  }
                  messageInfo.secondsOnQueue = (startTimeDate.time - sqsTimestampDate.time) / 1000.0
                  messageInfo.sqsTimestamp = DateUtil.formatUTC(sqsTimestampDate)
                  messageInfo.startTime = DateUtil.formatUTC(startTimeDate)

                  // if the flag is not set then delete immediately
                  if(!deleteMessageIfNoError&&!isCancelled()) sqsStagerService.deleteMessages(SqsUtils.sqsConfig.reader.queue, [message])
                  if((!isCancelled())&&sqsStagerService.checkMd5(message.mD5OfBody, message.body))
                  {
                     // log message start
                     def jsonMessage = sqsStagerService.parseMessage(message.body.toString())
                     downloadFile(jsonMessage)
                     if(isCancelled())
                     {
                        okToDelete = false
                        needToCleanup = true
                        messageInfo.httpStatus    = HttpStatus.BAD_REQUEST
                        messageInfo.statusMessage = JOB_CANCELLED_MESSAGE

                     }
                     else if(messageInfo.downloadStatus == HttpStatus.FOUND ||
                             messageInfo.downloadStatus == HttpStatus.OK)
                     {
                        HashMap dataInfoResult = sqsStagerService.getDataInfo(messageInfo.filename)
                        if(dataInfoResult)
                        {
                          stageFile()
                        }
                        else
                        {
                          messageInfo.stageStatus = HttpStatus.UNSUPPORTED_MEDIA_TYPE
                          messageInfo.stageMessage = "Unable to get DataInfo from File ${messageInfo.filename}"
                        }
                        // log message parsed
                        if(isCancelled())
                        {
                          needToCleanup = true
                          messageInfo.httpStatus = HttpStatus.BAD_REQUEST
                          messageInfo.stageMessage = JOB_CANCELLED_MESSAGE
                        }
                        else if(messageInfo.stageStatus != HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                        {
                           // Calculate secondsBeforeQueue
                           Date acquisitionDate = messageInfo.acquisitionDate
                           if(acquisitionDate)
                           {
                              // Convert to seconds. if the acquisition date was found
                              messageInfo.secondsBeforeQueue = (sqsTimestampDate.time - 
                                                               acquisitionDate.time) / 1000 
                           }
                           else
                           {
                              log.error "Acquisition date was not found/parsed."

                           }
                           indexRaster()

                           def addMetadataURL = OmarAvroUtils.avroConfig?.metadata?.addMetadataEndPoint
                           // if we have gotten to here the staging is complete so ignore cancel and finish the rest of 
                           // it for this is fast.
                           if(addMetadataURL)
                           {
                              Date postAvroMetadataStartTime = new Date()
                              log.info "Posting Avro Metadata to ${addMetadataURL}..."
                              HashMap avroMetadataResult = HttpUtils.postToAvroMetadata(addMetadataURL, message.body.toString())
                              if(avroMetadataResult?.status != HttpStatus.OK)
                              {
                                 okToDelete = false;
                                 log.error "Unable to post metadata.  ERROR: ${avroMetadataResult.message}"
                                 messageInfo.httpStatus = avroMetadataResult?.status
                                 messageInfo.statusMessage = "Unable to post metadata.  ERROR: ${avroMetadataResult.message}"
                              }
                              Date postAvroMetadataEndTime = new Date()
                              def duration = (postAvroMetadataEndTime.time - postAvroMetadataStartTime.time)/1000.0
                              messageInfo.postAvroMetadataStartTime = DateUtil.formatUTC(postAvroMetadataStartTime)
                              messageInfo.postAvroMetadataEndTime = DateUtil.formatUTC(postAvroMetadataEndTime)
                              messageInfo.postAvroMetadataDuration = duration
                              messageInfo.duration += duration
                           }
                        }
                        else
                        {
                           messageInfo.httpStatus = HttpStatus.UNSUPPORTED_MEDIA_TYPE
                           messageInfo.statusMessage = messageInfo.stageMessage
                           needToCleanup = true
                        }
                     }
                     else
                     {
                        okToDelete = false
                        needToCleanup = true
                        messageInfo.httpStatus    = messageInfo.downloadStatus
                        messageInfo.statusMessage = messageInfo.downloadMessage
                     }

                     messageInfo.endTime = DateUtil.formatUTC(new Date())
                     messageInfo.totalDurationSinceAcquisition = messageInfo.duration + messageInfo.secondsBeforeQueue + messageInfo.secondsOnQueue

                     log.info "MessageId: ${messageInfo.messageId}: Finished processing..."
                     log.info new JsonBuilder(messageInfo).toString()
                  }
                  else 
                  {

                     messageInfo.httpStatus = HttpStatus.BAD_REQUEST
                     if(!isCancelled())
                     {
                        messageInfo.statusMessage = "MessageId: ${messageInfo.messageId} ERROR: BAD MD5 Checksum For Message: ${messageBody}"
                        log.error "MessageId: ${messageInfo.messageId} ERROR: BAD MD5 Checksum For Message: ${messageBody}"
                     }
                     else
                     {
                        messageInfo.statusMessage = JOB_CANCELLED_MESSAGE
                        okToDelete = false
                     }
                     log.info new JsonBuilder(messageInfo).toString()
                  }
               }
               catch(e)
               {
                  okToDelete = false;
                  messageInfo.httpStatus = HttpStatus.NOT_FOUND
                  messageInfo.statusMessage = "MessageId: ${messageInfo.messageId} ERROR: ${e.toString()}"
                  log.error "MessageId: ${messageInfo.messageId} ERROR: ${e.toString()}"
                  log.info new JsonBuilder(messageInfo).toString()
               }
               if(deleteMessageIfNoError&&okToDelete)
               {
                  sqsStagerService.deleteMessages(config.reader.queue, [message])
               }
               // if we are cancelled and the flag to delete the message is not true then we need to cleanup
               if(needToCleanup)
               {
                  cleanupCurrentMessage()
               }
               if(isCancelled())
               {
                 sqsStagerJobService.jobFinished(this)
                 return
               }
            } // end for each message
            sqsStagerJobService.jobFinished(this)
         } // end while loop
      }
      else
      {
         messageInfo.httpStatus = HttpStatus.NOT_FOUND
         messageInfo.statusMessage = "No queue defined for SQS stager to read from."
         log.info new JsonBuilder(messageInfo).toString()
      }
   }

   private void processMessage(def message)
   {
      resetMessageInfo()
   }

   void resetMessageInfo()
   {
      messageInfo = [requestMethod: "SqsStagerJob",
                     httpStatus: HttpStatus.OK,
                     messageId: null,
                     sourceUri: "",
                     filename: "",
                     startTime: null,
                     downloadStartTime: null,
                     downloadEndTime: null,
                     downloadDuration: 0,
                     stageStartTime: null,
                     stageEndTime: null,
                     sqsTimestamp: null,
                     secondsBeforeQueue: 0,
                     secondsOnQueue: 0,
                     dataInfoStartTime: null,
                     dataInfoEndTime: null,
                     dataInfoDuration:0,
                     indexStartTime: null,
                     indexEndTime: null,
                     indexDuration: 0,
                     duration: 0,
                     totalDurationSinceAcquisition: 0
                     ]
   }

  private void downloadFile(def jsonMessage)
  {
    HashMap result = new HashMap(messageInfo)
    log.info "MessageId: ${messageInfo.messageId}: Downloading...."
    try{
      HashMap downloadResult   = sqsStagerService.downloadFile(jsonMessage)
      result.downloadStartTime = DateUtil.formatUTC(downloadResult.startTime)
      result.downloadEndTime   = DateUtil.formatUTC(downloadResult.endTime)
      result.downloadDuration  = downloadResult.duration/1000
      result.downloadStatus    = downloadResult.status
      result.downloadMessage   = downloadResult.message
      result.duration         += result.downloadDuration
      result.sourceUri         = downloadResult.source
      result.filename          = downloadResult.destination
      result.fileSize          = downloadResult.fileSize?:0
      result.acquisitionDate = downloadResult.acquisitionDate
      log.info "MessageId: ${messageInfo.messageId}: Downloaded ${downloadResult.source} to ${downloadResult.destination}: ${downloadResult.message}"
    }
    catch(e)
    {
      result.downloadStatus = HttpStatus.BAD_REQUEST
      result.downloadMessage = e.toString()
      log.error "Error downloading file: ${e}"
    }

    messageInfo = result
  }

  void stageFile()
  {
    def config = SqsUtils.sqsConfig
    HashMap result = new HashMap(messageInfo)
    HashMap stagerParams = config.stager?.params as HashMap
    try{
      stagerParams.filename = messageInfo.filename
      log.info "MessageId: ${messageInfo.messageId}: Staging file ${messageInfo.filename}"
      imageStager = new ImageStager()
      stagerParams.imageStager = imageStager
      stagerParams.failIfNoGeom = true
      def stageFileResult        = sqsStagerService.stageFileJni(stagerParams)
      if(stageFileResult.status != HttpStatus.OK) log.error stageFileResult.message
      result.stageStartTime = DateUtil.formatUTC(stageFileResult.startTime)
      result.stageEndTime   = DateUtil.formatUTC(stageFileResult.endTime)
      result.stageDuration  = stageFileResult.duration/1000
      result.duration      += result.stageDuration
      result.stageStatus    = stageFileResult.status
      result.stageMessage   = stageFileResult.message
      if(stageFileResult?.status == HttpStatus.UNSUPPORTED_MEDIA_TYPE)
      {
        try
        {
          log.error "Deleting file ${messageInfo.filename} with source URL: ${messageInfo.sourceUri}"
          if(messageInfo?.filename){
            File localFile = new File(messageInfo.filename)
            if(localFile.exists()) localFile.delete()
          }
        }
        catch(e)
        {
          log.error "Unable to delete file ${messageInfo.filename} Error: ${e}"
        }
      }

      log.info "MessageId: ${messageInfo.messageId}: Staged file ${messageInfo.filename} with status ${stageFileResult.message}"
    }
    catch(e)
    {
      result.stageStatus = HttpStatus.BAD_REQUEST
      result.stageMessage = e.toString()
      log.error "Error stageFile: ${e.getMessage()}"
    }
    finally{
       
       synchronized(cancelledLock)
       {
         imageStager?.delete()
         stagerParams?.imageStager = null
         imageStager = null
       }
    }
    messageInfo = result
  }

  void indexRaster()
  {
    HashMap result = new HashMap(messageInfo)

    try{
      log.info "MessageId: ${messageInfo.messageId}: Getting XML from file ${messageInfo.filename}"
      HashMap dataInfoResult = sqsStagerService.getDataInfo(messageInfo.filename)
      result.dataInfoStartTime = DateUtil.formatUTC(dataInfoResult.startTime)
      result.dataInfoEndTime   = DateUtil.formatUTC(dataInfoResult.endTime)
      result.dataInfoDuration  = dataInfoResult.duration/1000
      result.dataInfoStatus    = dataInfoResult.status
      result.dataInfoMessage   = dataInfoResult.message
      result.duration         += result.dataInfoDuration
      if(isCancelled())
      {
         result.indexStatus = HttpStatus.BAD_REQUEST
         result.indexMessage = JOB_CANCELLED_MESSAGE
      }
      else if(dataInfoResult.status == HttpStatus.OK)
      {
        log.info "MessageId: ${messageInfo.messageId}: Indexing file ${messageInfo.filename}"
        HashMap addRasterResult = rasterDataSetService.addRasterXml(dataInfoResult?.xml)
        result.indexStartTime  = DateUtil.formatUTC(addRasterResult.startTime)
        result.indexEndTime    = DateUtil.formatUTC(addRasterResult.endTime)
        result.indexDuration   = addRasterResult.duration/1000
        result.indexStatus     = addRasterResult.status
        result.indexMessage    = addRasterResult.message
        result.duration       += result.indexDuration
        if(addRasterResult.metadata) result = result<< addRasterResult.metadata
        log.info "MessageId: ${messageInfo.messageId}: Indexed file ${messageInfo.filename} with status ${messageInfo.indexMessage?:''}"
      }
      else
      {
        log.error "Error extracting XML: ${messageInfo.dataInfoMessage}"
      }
    }
    catch(e)
    {
      log.error "Error indexRaster: ${e}"
    }

    messageInfo = result

  }
}
