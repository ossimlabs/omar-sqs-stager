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
    def messages
    def config = SqsUtils.sqsConfig

    // do some validation
    // if these are not set then let's not pop any messages off and just
    // log the error and return
    //
    Boolean okToProceed = true
    if(!config?.stager?.addRaster?.url)
    {
      // need to log error
      okToProceed = false
    }
    if(!config?.reader?.queue)
    {
      // need to log error
      okToProceed = false
    }
    if(okToProceed)
    {
      while(messages = sqsService?.receiveMessages())
      {
       //ingestdate = new Date().format("yyyy-MM-dd HH:mm:ss.SSS")
        //ingestdate = DateUtil.formatUTC(new Date())
        def messagesToDelete = []
        def messageBodyList  = []
        HashMap stagerParams = config.stager.params as HashMap
        messages?.each{message->
          try{
            if(sqsService.checkMd5(message.mD5OfBody, message.body))
            {
              println "Got message and now parsing"
              // log message start
              def jsonMessage = sqsService.parseMessage(message.body.toString())
              println "Downloading"
              // log message parsed
              def downloadResult = sqsService.downloadFile(jsonMessage)
              println downloadResult.message
              stagerParams.filename = downloadResult.destination
              println "Staging"
              def stageFileResult = sqsService.stageFileJni(stagerParams)
              println stageFileResult.message
              println "Getting XML"
              HashMap dataInfoResult = sqsService.getDataInfo(downloadResult.destination)
              println dataInfoResult.message
              println "Posting XML"
              HashMap postResult = sqsService.postXml(config?.stager?.addRaster?.url, 
                                                      dataInfoResult?.xml)
              println postResult.message
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
            //e.printStackTrace()
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
