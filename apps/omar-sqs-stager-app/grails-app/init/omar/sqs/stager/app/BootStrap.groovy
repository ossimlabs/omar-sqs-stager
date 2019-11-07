package omar.sqs.stager.app

import grails.util.Holders
import org.quartz.TriggerKey
import org.quartz.JobKey
import org.quartz.impl.matchers.GroupMatcher
import joms.oms.Init
class BootStrap {
   def grailsApplication
    def init = { servletContext ->
      Init.instance().initialize()
      // grailsApplication = Holders.grailsApplication
      // SqsConfig.application = grailsApplication
      // SqsUtils.resetSqsConfig()
      // SqsUtils.sqsConfig
      def jobKeys = []
      def quartzScheduler = grailsApplication.mainContext.getBean('quartzScheduler')

      org.quartz.TriggerKey triggerKey = new TriggerKey("SqsReaderTrigger", "SqsReaderGroup")
      def trigger = quartzScheduler?.getTrigger(triggerKey)
      def jobDetails = quartzScheduler?.getJobDetail(new JobKey("SqsReaderTrigger", "SqsReaderGroup"))

      quartzScheduler?.jobGroupNames.each{
        quartzScheduler?.getJobKeys(GroupMatcher.groupEquals(it)).each{
          jobKeys << it  
        }
      }
      jobKeys.each{
        if(!it.name.contains("Sqs"))
        {
          quartzScheduler?.deleteJob(it)
        }
      }
      // if(SqsUtils.sqsConfig.reader.enabled)
      if(grailsApplication.config.omar.sqs.reader.enabled)
      {
         if(trigger)
         {
            // trigger.repeatInterval = SqsUtils.sqsConfig.reader.pollingIntervalSeconds*1000 as Long
            trigger.repeatInterval = grailsApplication.config.omar.sqs.reader.pollingIntervalSeconds?.toLong() * 1000 
            Date nextFireTime=quartzScheduler?.rescheduleJob(triggerKey, trigger)
         }
      }
      // if(SqsUtils.sqsConfig.reader.pauseOnStart)
      if(grailsApplication.config.omar.sqs.reader.pauseOnStart)
      {
        quartzScheduler?.pauseAll()
      }
    }
    def destroy = {
    }
}
