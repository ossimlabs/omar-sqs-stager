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
  def execute() {
    def messages
    def config = SqsUtils.sqsConfig

    // do some validation
    // if these are not set then let's not pop any messages off and just
    // log the error and return
    //
    Boolean okToProceed = true
    if(!config?.reader?.queue)
    {
      // need to log error
      okToProceed = false
    }
    if(okToProceed)
    {
      while(messages = sqsService?.receiveMessages())
      {
        //ingestdate = DateUtil.formatUTC(new Date())
        messages?.each{message->
          HashMap messageInfo = [ requestMethod: "SqsStagerJob",
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
          try{
            messageInfo.messageId = message?.messageId

            sqsService.deleteMessages(SqsUtils.sqsConfig.reader.queue,
                                      [message])
            if(sqsService.checkMd5(message.mD5OfBody, message.body))
            {
              log.info "MessageId: ${messageInfo.messageId}"
              // log message start
              def jsonMessage = sqsService.parseMessage(message.body.toString())
              messageInfo = downloadFile(messageInfo,jsonMessage)
              // log message parsed
              messageInfo = stageFile(messageInfo)
              if(messageInfo.stageStatus != HttpStatus.UNSUPPORTED_MEDIA_TYPE)
              {
                messageInfo = indexRaster(messageInfo)
              }

              def addMetadataURL = OmarAvroUtils.avroConfig?.metadata?.addMetadataEndPoint
              if(addMetadataURL)
              {
                Date postAvroMetadataStartTime = new Date()
                log.info "Posting Avro Metadata to ${addMetadataURL}..."
                HashMap avroMetadataResult = HttpUtils.postToAvroMetadata(addMetadataURL, message.body.toString())
                if(avroMetadataResult?.status != HttpStatus.OK)
                {
                  log.error "Unable to post metadata.  ERROR: ${avroMetadataResult.message}"
                }
                Date postAvroMetadataEndTime = new Date()
                Integer duration = (postAvroMetadataEndTime.time - postAvroMetadataStartTime.time)/1000
                messageInfo.postAvroMetadataStartTime = DateUtil.formatUTC(postAvroMetadataStartTime)
                messageInfo.postAvroMetadataEndTime = DateUtil.formatUTC(postAvroMetadataEndTime)
                messageInfo.postAvroMetadataDuration = duration
                messageInfo.duration += duration
              }
              messageInfo.endTime = DateUtil.formatUTC(new Date())

              log.info "MessageId: ${messageInfo.messageId}: Finished processing..."
              log.info new JsonBuilder(messageInfo).toString()
            }
            else
            {
              log.error "MessageId: ${messageInfo.messageId} ERROR: BAD MD5 Checksum For Message: ${messageBody}"
            }
          }
          catch(e)
          {
            log.error "MessageId: ${messageInfo.messageId} ERROR: ${e.toString()}"
          }
        }
      }
    }
    else
    {
      log.error "No queue defined for SQS stager to read from."
    }
  }
}
