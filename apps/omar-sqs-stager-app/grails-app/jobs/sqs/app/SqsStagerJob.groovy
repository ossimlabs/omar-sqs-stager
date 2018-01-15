package sqs.app

import groovy.json.JsonBuilder
import groovy.json.JsonSlurper
import omar.core.DateUtil
import omar.avro.HttpUtils
import omar.core.HttpStatus
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
        HashMap stagerParams = config.stager.params as HashMap
        messages?.each{message->
          HashMap messageInfo = [ requestMethod: "SqsStagerJob",
                                  messageId:null,
                                  sourceUri: "",
                                  filename: "",
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
              log.info "MessageId: ${messageInfo.messageId}: Downloading....."

              // log message parsed
              def downloadResult = sqsService.downloadFile(jsonMessage)
              messageInfo.downloadStartTime = downloadResult.startTime
              messageInfo.downloadEndTime   = downloadResult.endTime
              messageInfo.downloadDuration  = downloadResult.duration/1000
              messageInfo.duration         += messageInfo.downloadDuration
              messageInfo.sourceUri         = downloadResult.source 
              messageInfo.filename          = downloadResult.destination 
              stagerParams.filename         = downloadResult.destination
              log.info "MessageId: ${messageInfo.messageId}: Downloaded ${downloadResult.source} to ${downloadResult.destination}: ${downloadResult.message}"
              log.info "MessageId: ${messageInfo.messageId}: Staging file ${stagerParams.filename} with message id: ${messageInfo.messageId}"
              def stageFileResult = sqsService.stageFileJni(stagerParams)
              if(stageFileResult.status != HttpStatus.OK) log.error stageFileResult.message
              messageInfo.stageStartTime = stageFileResult.startTime
              messageInfo.stageEndTime = stageFileResult.endTime
              messageInfo.stageDuration = stageFileResult.duration/1000
              messageInfo.duration += messageInfo.stageDuration

              log.info "MessageId: ${messageInfo.messageId}: Getting XML from file ${stagerParams.filename}"
              HashMap dataInfoResult = sqsService.getDataInfo(downloadResult.destination)
              messageInfo.dataInfoStartTime = dataInfoResult.startTime
              messageInfo.dataInfoEndTime   = dataInfoResult.endTime
              messageInfo.dataInfoDuration  = dataInfoResult.duration/1000
              messageInfo.duration         += messageInfo.dataInfoDuration
              if(dataInfoResult.status != HttpStatus.OK) log.error dataInfoResult.message


              log.info "MessageId: ${messageInfo.messageId}: Indexing file ${stagerParams.filename}"
              HashMap addRasterResult = rasterDataSetService.addRasterXml(dataInfoResult?.xml)
              ingestMetricsService.endIngest(messageInfo.filename)
              messageInfo.indexStartTime  = addRasterResult.startTime
              messageInfo.indexEndTime    = addRasterResult.endTime
              messageInfo.indexDuration   = addRasterResult.duration/1000
              messageInfo.duration       += messageInfo.indexDuration
              messageInfo.metadata        = addRasterResult.metadata
              if(addRasterResult.status != HttpStatus.OK) log.error addRasterResult.message

              log.info "MessageId: ${messageInfo.messageId}: Finished processing message ${messageInfo}"
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
