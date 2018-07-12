package sqs.app

import groovy.json.JsonBuilder
import groovy.json.JsonSlurper
import groovy.time.TimeCategory
import groovy.time.TimeDuration
import omar.core.DateUtil
import omar.avro.HttpUtils
import omar.core.HttpStatus
import omar.avro.OmarAvroUtils
import groovy.json.JsonBuilder
import groovy.transform.Synchronized

import java.time.Duration

class SqsStagerJob {
   def sqsStagerService
   def sqsStagerJobService
   def avroService
   def rasterDataSetService
   static def concurrent = false
   static triggers = {
      simple startDelay: 1000l, repeatInterval: 1000l, name: 'SqsReaderTrigger', group: 'SqsReaderGroup'
   }


  def execute() {
    // returns the old value and sets to true
    // if the old value was already true then return
    // This allows for an atomic check and set of the value.
    if(sqsStagerJobService?.setAndCheckJobScheduled(true)) return;

    SqsStagerJobUtil jobInfo = sqsStagerJobService.newJob([sqsStagerService: sqsStagerService,
                                                           sqsStagerJobService: sqsStagerJobService,
                                                           avroService: avroService,
                                                           rasterDataSetService: rasterDataSetService
                                                           ])
    try{
      jobInfo.execute()
    }
    catch(e)
    {
      
    }

    sqsStagerJobService?.setAndCheckJobScheduled(false)
  }
}
