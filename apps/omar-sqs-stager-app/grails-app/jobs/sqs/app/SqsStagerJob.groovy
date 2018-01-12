package sqs.app

import groovy.json.JsonBuilder
import groovy.json.JsonSlurper
import omar.core.DateUtil
import omar.avro.HttpUtils
import omar.core.HttpStatus

class SqsStagerJob {
   def sqsService
   def avroService
   def ingestMetricsService
   def concurrent = false

   static triggers = {
      simple repeatInterval: 1000l, name: 'SqsReaderTrigger', group: 'SqsReaderGroup'
   }

  def execute() {
    Boolean keepGoing = true
    def messages
    def config = SqsUtils.sqsConfig
    def ingestdate
    if(config.reader.queue)
    {

      while(messages = sqsService?.receiveMessages())
      {
       //ingestdate = new Date().format("yyyy-MM-dd HH:mm:ss.SSS")
        ingestdate = DateUtil.formatUTC(new Date())

        def messagesToDelete = []
        def messageBodyList  = []
        HashMap stagerParams = config.stager.params as HashMap
        String url
        messages?.each{message->
          try{
            if(sqsService.checkMd5(message.mD5OfBody, message.body))
            {
              // log message start
              def jsonMessage = sqsService.parseMessage(message.body.toString())

              // log message parsed
              def downloadResult = sqsService.downloadFile(jsonMessage)
              println downloadResult

              // need to log downloadResult.duration , startTime, endTime, destination and source and errorMessage
              
              stagerParams.filename = downloadResult.destination
              def stageFileResult = sqsService.stageFileJni(stagerParams)
              println stageFileResult

              String xml = sqsService.getDataInfo(downloadResult.destination)

              HashMap postResult = sqsService.postXml(xml)
              println postResult
              messagesToDelete << message
            }
            else
            {
              log.error "ERROR: BAD MD5 Checksum For Message: ${messageBody}"
              messagesToDelete << message
            }
          }
          catch(e)
          {
            e.printStackTrace()
            log.error "ERROR: ${e.toString()}"
          }

          messageBodyList = []
        }
        if(messagesToDelete) sqsService.deleteMessages(
                                       SqsUtils.sqsConfig.reader.queue,
                                       messagesToDelete)
        messagesToDelete = []

      }
    }
  }
}
