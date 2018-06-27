package sqs.app

import grails.gorm.transactions.Transactional
import java.util.UUID;
import groovy.transform.Synchronized
import omar.core.HttpStatus
import org.quartz.TriggerKey
import org.quartz.JobKey
import org.quartz.Trigger.TriggerState
import groovy.json.JsonSlurper
import org.springframework.beans.factory.InitializingBean

@Transactional
class SqsStagerJobService implements InitializingBean {
   HashMap currentJobs = [:]
   private final globalLock = new Object()
   private final jobScheduledLock = new Object()
   Boolean jobScheduledFlag = false
   def grailsApplication
   def discoveryClient  
   String sqsStagerInstanceName="omar-sqs-stager"


   /** 
   * seems that there is a quartz problem so I added a synchronize here to do an 
   * atomic operation to set the new state and check the old state of the scheduled job
   */
    @Synchronized('jobScheduledLock')
    Boolean setAndCheckJobScheduled(Boolean value)
    {
       Boolean result = jobScheduledFlag
       jobScheduledFlag = value
       result
    }
    
    /**
    * @return a new job hash ID
    */
    @Synchronized('globalLock')
    SqsStagerJobUtil newJob(HashMap params) {
        if(!params) params = [:]
        params.id = UUID.randomUUID().toString()
        SqsStagerJobUtil result = new SqsStagerJobUtil(params)
        if(!result.messageInfo)
        {
           result.resetMessageInfo()
        } 

        result
    }   
    @Synchronized('globalLock')
    void jobStarted(SqsStagerJobUtil jobInfo)
    {
        currentJobs[jobInfo.id] = jobInfo
    }

    @Synchronized('globalLock')
    void jobFinished(SqsStagerJobUtil jobInfo)
    {
       currentJobs.remove(jobInfo.id)
    }

    private Boolean isQuartzQueuePaused()
    {
       Boolean result = false
      def quartzScheduler = grailsApplication.mainContext.getBean('quartzScheduler')
      switch(quartzScheduler?.getTriggerState(new TriggerKey("SqsReaderTrigger", "SqsReaderGroup")))
      {
         case TriggerState.PAUSED:
            result = true
            break
         default:
            break
      }
      result
    } 

    HashMap stop(SqsStagerCommand cmd)
    {
      HashMap result = [
                        result: true
                       ]
      def quartzScheduler = grailsApplication.mainContext.getBean('quartzScheduler')
      quartzScheduler?.pauseAll()
      try{
         currentJobs.each{k,v->
            v?.cancel()
         }
         if(!cmd.recurseFlag)
         {
            cmd.recurseFlag = true
            discoveryClient.getInstances(sqsStagerInstanceName)?.each{it->
               String value = new URL("${it.uri}/sqsStager/stop?${cmd.toUrlQuery()}").text
            } 
         }
      }
      catch(e)
      {
         result.result = false
         result.message = e.toString()
      }

      result
    }

    HashMap pause(SqsStagerCommand cmd)
    {
      HashMap result = [
                        result: true
                       ]
      def quartzScheduler = grailsApplication.mainContext.getBean('quartzScheduler')
      quartzScheduler?.pauseAll()
      try{
         if(!cmd.recurseFlag)
         {
            cmd.recurseFlag = true
            discoveryClient.getInstances(sqsStagerInstanceName)?.each{it->
               String value = new URL("${it.uri}/sqsStager/pause?${cmd.toUrlQuery()}").text
            } 
         }
      }
      catch(e)
      {
         result.result = false
         result.message = e.toString()
      }

      result
    }

    HashMap start(SqsStagerCommand cmd)
    {
      HashMap result = [
                        result:true
                     ]
      def quartzScheduler = grailsApplication.mainContext.getBean('quartzScheduler')
      quartzScheduler?.resumeAll()
      try{
         if(!cmd.recurseFlag)
         {
            cmd.recurseFlag = true
            discoveryClient.getInstances(sqsStagerInstanceName)?.each{it->
               String value = new URL("${it.uri}/sqsStager/start?${cmd.toUrlQuery()}").text
            } 
         }
     }
      catch(e)
      {
         result.result = false
         result.message = e.toString()
      }

      result
    }

    private HashMap queryBoolean(String path, SqsStagerCommand cmd, Boolean andOperation = false)
    {
       HashMap result = [result: andOperation]

       // if the recurse is set then we are already looping just return my result
      cmd.recurseFlag = true
      discoveryClient.getInstances(sqsStagerInstanceName)?.each{it->
         String value = new URL("${it.uri}${path}?${cmd.toUrlQuery()}").text
         if(value)
         {
            try{
               JsonSlurper slurper = new JsonSlurper()
               def jsonRoot = slurper.parseText(value)
               
               if(andOperation)
               {
                  if(!jsonRoot?.result)
                  {
                     result = [result : false]
                     return result
                  }
               }
               else
               {
                  if(jsonRoot?.result)
                  {
                     result = [result : true]
                     return result
                  }
               }
            }
            catch(e)
            {

            }
         }
      }

       result

    }

    HashMap isPaused(SqsStagerCommand cmd)
    {
       HashMap result = [result: false]
       // if the recurse is set then we are already looping just return my result
       if(cmd.recurseFlag)
       {
          result = [result: isQuartzQueuePaused()]
       }
       else
       {
          // all must be paused in order to return true
          result = queryBoolean("/sqsStager/isPaused", cmd, true)
       }

       result
    }

    HashMap isProcessingJobs(SqsStagerCommand cmd)
    {
       HashMap result = [result: false]
       // if the recurse is set then we are already looping just return my result
       if(cmd.recurseFlag)
       {
          result = [result: currentJobs.size()>0]
       }
       else
       {
          result = queryBoolean("/sqsStager/isProcessingJobs", cmd, false)
       }

       result
    }
    HashMap isProcessing(SqsStagerCommand cmd)
    {
       HashMap result = [result: false]
       // if the recurse is set then we are already looping just return my result
       if(cmd.recurseFlag)
       {
          Boolean value = ((currentJobs.size()>0)||!isQuartzQueuePaused())
          result = [result: value]
       }
       else
       {
          // if any are not processing I think we should return false
          result = queryBoolean("/sqsStager/isProcessing", cmd, true)
       }

       result
    }
    public void afterPropertiesSet() throws Exception 
    {
      if(grailsApplication.config.spring.application.name)
      {
         sqsStagerInstanceName = grailsApplication.config.spring.application.name
      }
    }
}
