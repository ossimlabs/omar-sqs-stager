package sqs.app

import groovy.json.JsonBuilder
import groovy.json.JsonSlurper
import groovy.time.TimeDuration
import omar.core.DateUtil
import omar.avro.HttpUtils
import omar.core.HttpStatus
import omar.avro.OmarAvroUtils
import groovy.json.JsonBuilder

import java.time.Duration

class SqsStagerJob {
   def sqsStagerService
   def avroService
   def rasterDataSetService
   def concurrent = false

   static triggers = {
      simple repeatInterval: 1000l, name: 'SqsReaderTrigger', group: 'SqsReaderGroup'
   }

  private HashMap downloadFile(HashMap messageInfo, def jsonMessage)
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
      log.info "MessageId: ${messageInfo.messageId}: Downloaded ${downloadResult.source} to ${downloadResult.destination}: ${downloadResult.message}"
    }
    catch(e)
    {
      result.downloadStatus = HttpStatus.BAD_REQUEST
      result.downloadMessage = e.toString()
      log.error "Error downloading file: ${e}"
    }

    result
  }
  HashMap stageFile(HashMap messageInfo)
  {
    def config = SqsUtils.sqsConfig
    HashMap result = new HashMap(messageInfo)
    HashMap stagerParams = config.stager?.params as HashMap
    try{

      stagerParams.filename = messageInfo.filename
      log.info "MessageId: ${messageInfo.messageId}: Staging file ${messageInfo.filename}"
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

    result
  }
  HashMap indexRaster(HashMap messageInfo)
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
      if(dataInfoResult.status == HttpStatus.OK)
      {
        log.info "MessageId: ${messageInfo.messageId}: Indexing file ${messageInfo.filename}"
        HashMap addRasterResult     = rasterDataSetService.addRasterXml(dataInfoResult?.xml)
        result.indexStartTime  = DateUtil.formatUTC(addRasterResult.startTime)
        result.indexEndTime    = DateUtil.formatUTC(addRasterResult.endTime)
        result.indexDuration   = addRasterResult.duration/1000
        result.indexStatus     = addRasterResult.status
        result.indexMessage    = addRasterResult.message
        result.duration       += result.indexDuration
        if(addRasterResult.metadata) result = result<< addRasterResult.metadata
        log.info "MessageId: ${messageInfo.messageId}: Indexed file ${messageInfo.filename} with status ${messageInfo.indexMessage}"
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
    result
  }
  HashMap newMessageInfo(){
     [requestMethod: "SqsStagerJob",
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
      secondsOnQueue: 0,
      dataInfoStartTime: null,
      dataInfoEndTime: null,
      dataInfoDuration:0,
      indexStartTime: null,
      indexEndTime: null,
      indexDuration: 0,
      duration:0]
  }
  def execute() {
    def messages
    def config = SqsUtils.sqsConfig

    // do some validation
    // if these are not set then let's not pop any messages off and just
    // log the error and return
    //
    Boolean okToProceed = true
    Boolean deleteMessageIfNoError = config.reader.deleteMessageIfNoError?:false
    if(!config?.reader?.queue)
    {
      // need to log error
      okToProceed = false
    }
    HashMap messageInfo
    if(okToProceed)
    {
      String timestampName = config.reader.timestampName?:""

      while(messages = sqsStagerService?.receiveMessages())
      {
        messages?.each{message->
          Boolean okToDelete = true
          messageInfo = newMessageInfo()
          def startTimeDate = new Date()
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

            Date acquisitionDate = DateUtil.parseDate(json."acquisitionDates" as String) ?: new Date()
            println("DEBUG: Acq date = $acquisitionDate")
            TimeDuration acquisitionToStartTime = null
            if (acquisitionDate instanceof Date) {
              use(groovy.time.TimeCategory) {
                acquisitionToStartTime = startTimeDate - acquisitionDate
              }
            }
            messageInfo.acquisitionToStartTime = acquisitionToStartTime.toMilliseconds()
            println("DEBUG: acquisitionToStartTime = $acquisitionToStartTime")

            // if the flag is not set then delete immediately
            if(!deleteMessageIfNoError) sqsStagerService.deleteMessages(SqsUtils.sqsConfig.reader.queue, [message])
            if(sqsStagerService.checkMd5(message.mD5OfBody, message.body))
            {
              // log message start
              def jsonMessage = sqsStagerService.parseMessage(message.body.toString())
              messageInfo = downloadFile(messageInfo,jsonMessage)
              if(messageInfo.downloadStatus == HttpStatus.FOUND ||
                 messageInfo.downloadStatus == HttpStatus.OK)
              {
              // log message parsed
                messageInfo = stageFile(messageInfo)
                if(messageInfo.stageStatus != HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                {
                  messageInfo = indexRaster(messageInfo)

                  def addMetadataURL = OmarAvroUtils.avroConfig?.metadata?.addMetadataEndPoint
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
                }
              }
              else
              {
                  okToDelete = false;
                  messageInfo.httpStatus        = messageInfo.downloadStatus
                  messageInfo.statusMessage = messageInfo.downloadMessage
              }

              messageInfo.endTime = DateUtil.formatUTC(new Date())

              log.info "MessageId: ${messageInfo.messageId}: Finished processing..."
              log.info new JsonBuilder(messageInfo).toString()
            }
            else
            {
              messageInfo.httpStatus = HttpStatus.BAD_REQUEST
              messageInfo.statusMessage = "MessageId: ${messageInfo.messageId} ERROR: BAD MD5 Checksum For Message: ${messageBody}"
              log.error "MessageId: ${messageInfo.messageId} ERROR: BAD MD5 Checksum For Message: ${messageBody}"
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
        }
      }
    }
    else
    {
      messageInfo.httpStatus = HttpStatus.NOT_FOUND
      messageInfo.statusMessage = "No queue defined for SQS stager to read from."
      log.info new JsonBuilder(messageInfo).toString()
      log.error messageInfo.statusMessage
    }
  }
}
