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
   String contextPath="/"

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
        result.cancelled = false
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
   String getDiscoveryUri(def discoveryUri)
   {
      "${discoveryUri.uri}${contextPath}"
   }


    private HashMap queryBoolean(String path, SqsStagerCommand cmd, Boolean andOperation = false)
    {
       HashMap result = [result: andOperation]

       // if the recurse is set then we are already looping just return my result
      cmd.recurseFlag = true
      discoveryClient.getInstances(sqsStagerInstanceName)?.each{it->
         String value = new URL("${getDiscoveryUri(it)}${path}?${cmd.toUrlQuery()}").text
         if(value)
         {
            try{
               JsonSlurper slurper = new JsonSlurper()
               def jsonRoot = slurper.parseText(value)
               if(jsonRoot?.message)
               {
                  result.message = jsonRoot.message
               }
               if(andOperation)
               {
                  if(!jsonRoot?.result)
                  {
                     result.result=false
                     return result
                  }
               }
               else
               {
                  if(jsonRoot?.result)
                  {
                     result.result=true
                     return result
                  }
               }
            }
            catch(e)
            {
               result = [result : false, message: toString()]

            }
         }
      }

      result

    }

    HashMap stop(SqsStagerCommand cmd)
    {
      HashMap result = [
                        result: true
                       ]
      try{
         def quartzScheduler = grailsApplication.mainContext.getBean('quartzScheduler')
         quartzScheduler?.pauseAll()
         currentJobs.each{k,v->
            v?.cancel()
         }
         if(!cmd.recurseFlag)
         {
          result = queryBoolean("/sqsStager/stop", cmd, true)
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
      try{
         def quartzScheduler = grailsApplication.mainContext.getBean('quartzScheduler')
         quartzScheduler?.pauseAll()
         if(!cmd.recurseFlag)
         {
          result = queryBoolean("/sqsStager/pause", cmd, true)
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
                        result: true
                       ]
      try{
         def quartzScheduler = grailsApplication.mainContext.getBean('quartzScheduler')
         quartzScheduler?.resumeAll()
         if(!cmd.recurseFlag)
         {
          result = queryBoolean("/sqsStager/start", cmd, true)
         }
      }
      catch(e)
      {
         result.result = false
         result.message = e.toString()
      }

      result
    }

    HashMap isPaused(SqsStagerCommand cmd)
    {
       HashMap result = [result: isQuartzQueuePaused()]
       if(!cmd.recurseFlag)
       {
          // all must be paused in order to return true
          result = queryBoolean("/sqsStager/isPaused", cmd, true)
       }

       result
    }

    HashMap isProcessingJobs(SqsStagerCommand cmd)
    {
       HashMap result = [result: currentJobs.size()>0]
       // if the recurse is set then we are already looping just return my result
       if(!cmd.recurseFlag)
       {
          result = queryBoolean("/sqsStager/isProcessingJobs", cmd, false)
       }

       result
    }
    HashMap isProcessing(SqsStagerCommand cmd)
    {
       HashMap result = [result: false]
       // if the recurse is set then we are already looping just return my result
      Boolean value = ((currentJobs.size()>0)||!isQuartzQueuePaused())
      result = [result: value]
      if(!cmd.recurseFlag)
      {
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
      if(grailsApplication.config.server.contextPath)
      {
         contextPath = grailsApplication.config.server.contextPath
      }
    }
}
