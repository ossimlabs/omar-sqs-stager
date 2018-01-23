package sqs.app

import groovy.json.JsonBuilder
import groovy.json.JsonSlurper
import omar.core.DateUtil
import omar.avro.HttpUtils
import omar.core.HttpStatus
import omar.avro.OmarAvroUtils
import groovy.json.JsonBuilder

class SqsStagerJob {
   def sqsService
   def avroService
   def ingestMetricsService
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
      HashMap downloadResult   = sqsService.downloadFile(jsonMessage)
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
      def stageFileResult        = sqsService.stageFileJni(stagerParams)
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
      log.error "Error stageFile: ${e}"
    }

    result
  }
  HashMap indexRaster(HashMap messageInfo)
  {
    HashMap result = new HashMap(messageInfo)

    try{
      log.info "MessageId: ${messageInfo.messageId}: Getting XML from file ${messageInfo.filename}"
      HashMap dataInfoResult = sqsService.getDataInfo(messageInfo.filename)
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
        ingestMetricsService.endIngest(messageInfo.filename)
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
      status: HttpStatus.OK,
      messageId:null,
      sourceUri: "",
      filename: "",
      startTime: DateUtil.formatUTC(new Date()), 
      downloadStartTime:null,
      downloadEndTime: null,
      downloadDuration: 0,
      stageStartTime:null,
      stageEndTime: null,
      dataInfoStartTime:null,
      dataInfoEndTime:null,
      dataInfoDuration:0,
      indexStartTime: null,
      indexEndTime: null,
      indexDuration: 0,
      duration:0]  
  }
  def execute() {
    def messages
    def config = SqsUtils.sqsConfig
    String timestampAtributeName = config.reader.timestampAttribute?:""
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
      while(messages = sqsService?.receiveMessages())
      {
        //ingestdate = DateUtil.formatUTC(new Date())
        messages?.each{message->
          Boolean okToDelete = true
          messageInfo = newMessageInfo()
          try{
            messageInfo.messageId = message?.messageId
            // if the flag is not set then delete immediately
            if(!deleteMessageIfNoError) sqsService.deleteMessages(SqsUtils.sqsConfig.reader.queue, [message])
            if(sqsService.checkMd5(message.mD5OfBody, message.body))
            {
              if(timestampAtributeName)
              {
                messageInfo.sqsTimestamp = message?.attrributres."${timestampAtributeName}"
              }
              // log message start
              def jsonMessage = sqsService.parseMessage(message.body.toString())
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
                      messageInfo.status = avroMetadataResult?.status
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
                  messageInfo.status = HttpStatus.UNSUPPORTED_MEDIA_TYPE
                  messageInfo.statusMessage = messageInfo.stageMessage   
                }
              }
              else
              {
                  okToDelete = false;
                  messageInfo.status        = messageInfo.downloadStatus
                  messageInfo.statusMessage = messageInfo.downloadMessage   
              }

              messageInfo.endTime = DateUtil.formatUTC(new Date())

              log.info "MessageId: ${messageInfo.messageId}: Finished processing..."
              log.info new JsonBuilder(messageInfo).toString()
            }
            else
            {
              messageInfo.status = HttpStatus.BAD_REQUEST
              messageInfo.statusMessage = "MessageId: ${messageInfo.messageId} ERROR: BAD MD5 Checksum For Message: ${messageBody}"
              log.error "MessageId: ${messageInfo.messageId} ERROR: BAD MD5 Checksum For Message: ${messageBody}"
              log.info new JsonBuilder(messageInfo).toString()
            }
          }
          catch(e)
          {
            okToDelete = false;
            messageInfo.status = HttpStatus.NOT_FOUND
            messageInfo.statusMessage = "MessageId: ${messageInfo.messageId} ERROR: ${e.toString()}"
            log.error "MessageId: ${messageInfo.messageId} ERROR: ${e.toString()}"
            log.info new JsonBuilder(messageInfo).toString()
          }

          if(deleteMessageIfNoError&&okToDelete) 
          {
            sqsService.deleteMessages(config.reader.queue, [message])
          }
        }
      }
    }
    else
    {
      messageInfo.status = HttpStatus.NOT_FOUND
      messageInfo.statusMessage = "No queue defined for SQS stager to read from."
      log.info new JsonBuilder(messageInfo).toString()
      log.error messageInfo.statusMessage
    }
  }
}
