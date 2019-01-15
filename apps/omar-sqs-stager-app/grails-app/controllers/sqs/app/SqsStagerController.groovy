package sqs.app
import omar.core.BindUtil
import grails.converters.JSON
import io.swagger.annotations.*

@Api( value = "/sqsStager",
      description = "API operations for SQS Stager"
)
class SqsStagerController {

    def sqsStagerJobService

  @ApiOperation(value = "Allows one to stop the pulling of the SQS queue and will try to stop any running jobs",
                produces="application/json",
                httpMethod="POST",
                notes = """
                Will stop the processing of jobs and will pause the queue
                """)
    def stop()
    {
        def jsonData = request.JSON?request.JSON as HashMap:null
        def requestParams = params - params.subMap( ['controller', 'action'] )
        def cmd = new SqsStagerCommand()

        // get map from JSON and merge into parameters
        if(jsonData) requestParams << jsonData
        BindUtil.fixParamNames( SqsStagerCommand, requestParams )
        bindData( cmd, requestParams )

        HashMap result = sqsStagerJobService.stop(cmd)
        render result as JSON
    }
    @ApiOperation(value = "Allows one to pause the pulling of the SQS queue and will allow any current jobs to continue running",
                produces="application/json",
                httpMethod="POST",
                notes = """
                Will only pauses the pullling of messages of the SQS queue
                """)
    def pause()
    {
        def jsonData = request.JSON?request.JSON as HashMap:null
        def requestParams = params - params.subMap( ['controller', 'action'] )
        def cmd = new SqsStagerCommand()

        // get map from JSON and merge into parameters
        if(jsonData) requestParams << jsonData
        BindUtil.fixParamNames( SqsStagerCommand, requestParams )
        bindData( cmd, requestParams )

        HashMap result = sqsStagerJobService.pause(cmd)
        render result as JSON
    }


    @ApiOperation(value = "Will restart a paused or stopped queue",
                produces="application/json",
                httpMethod="POST",
                notes = """
                """)
    def start()
    {
        def jsonData = request.JSON?request.JSON as HashMap:null
        def requestParams = params - params.subMap( ['controller', 'action'] )
        def cmd = new SqsStagerCommand()

        // get map from JSON and merge into parameters
        if(jsonData) requestParams << jsonData
        BindUtil.fixParamNames( SqsStagerCommand, requestParams )
        bindData( cmd, requestParams )

        HashMap result = sqsStagerJobService.start(cmd)
        render result as JSON
    }
    @ApiOperation(value = "Returns a value true or false if the queue is paused",
                produces="application/json",
                httpMethod="GET",
                notes = """
                This endpoint will return {result:true} if the queue has been paused by either a previous call to 
                **sqsStager/pause** or to **sqsStager/stop**. Will return {result:false} otherwise.
                """)
    def isPaused()
    {
        def jsonData = request.JSON?request.JSON as HashMap:null
        def requestParams = params - params.subMap( ['controller', 'action'] )
        def cmd = new SqsStagerCommand()

        // get map from JSON and merge into parameters
        if(jsonData) requestParams << jsonData
        BindUtil.fixParamNames( SqsStagerCommand, requestParams )
        bindData( cmd, requestParams )

        HashMap result = sqsStagerJobService.isPaused(cmd)
        render result as JSON
    }
    @ApiOperation(value = "Returns a value true or false if the queue is not paused or if there are any currently running jobs",
                produces="application/json",
                httpMethod="GET",
                notes = """
                This endpoint will return {result:true} if the queue has not been paused or stopped 
                or if there are any currently running jobs.
                """)
    def isProcessing()
    {
        def jsonData = request.JSON?request.JSON as HashMap:null
        def requestParams = params - params.subMap( ['controller', 'action'] )
        def cmd = new SqsStagerCommand()

        // get map from JSON and merge into parameters
        if(jsonData) requestParams << jsonData
        BindUtil.fixParamNames( SqsStagerCommand, requestParams )
        bindData( cmd, requestParams )

        HashMap result = sqsStagerJobService.isProcessing(cmd)
        render result as JSON
    }
    @ApiOperation(value = "Returns a value true or false if there are any currently running jobs",
                produces="application/json",
                httpMethod="GET",
                notes = """
                This endpoint will return {result:true} if there are any currently running jobs.
                """)
    def isProcessingJobs()
    {
        def jsonData = request.JSON?request.JSON as HashMap:null
        def requestParams = params - params.subMap( ['controller', 'action'] )
        def cmd = new SqsStagerCommand()

        // get map from JSON and merge into parameters
        if(jsonData) requestParams << jsonData
        BindUtil.fixParamNames( SqsStagerCommand, requestParams )
        bindData( cmd, requestParams )

        HashMap result = sqsStagerJobService.isProcessingJobs(cmd)
        render result as JSON
    }
}
