package sqs.app
import omar.core.BindUtil
import grails.converters.JSON

class SqsStagerController {

    def sqsStagerJobService

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
