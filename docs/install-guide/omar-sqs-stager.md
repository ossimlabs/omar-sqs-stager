# OMAR SQS STAGER

omar-sqs-stager pulls avro formatted messages off an SQS queue and will download the referenced file and cache locally in a cache directory and stage and index the file into an omar database.  The omar-sqs-stager service will log as a final result a JSON formatted string of the form:

# Endpoint API

We have enabled API access to the staging queue to allow one to **pause**, **stop**, **start**, **isPaused**, **isProcessing**, and **isProcessingJobs**.   The service will use the discoveryClient to get all services currently active by using the interface to omar-eureka-server.  There it will obtain a list of sqs-stager services and will apply the endpoint calls to all services.

* **stop** Allows one to pause the job and will attemt a request to stop any staging the job has started.  You should call **isProcessing** to verify that the staging has stopped and the queue is paused.
* **pause** Allows one to pause the job but currently running stagers will continue to run until finished.  If you want to know if the job is officially paused then call **isPaused** endpoint
* **start** Will unpause a paused job and allow the messages to start popping off the queue
* **isPaused** will return a JSON {"result":"true"} if all sqs jobs are actually pasued or {result:false} otherwise
* **isProcessing** Will return a JSON {"result":"true"} if all sqs jobs are paused and all current sqs stagers running have finished otherwise it return {"result":"false"}
* **isProcessingJobs** Will return JSON {"result":"true"} if there are still stager jobs running and processing files else it will return {"result":"false"} 

Example calls:

`curl http://localhost:8080/sqsStager/isPaused`
`curl -X POST http://localhost:8080/sqsStager/stop`

Make sure you change the URL and context path based on your installation.  Most of our installation will have a context path and might look something similar to:

`wget http://<URL>/omar-sqs-stager/sqsStager/isPaused`

# Log Output

**JSON Staging log:**

```
{  
   "acquisitionDate":"2008-03-11T01:05:00+0000",
   "stageStatus":200,
   "indexStartTime":"2018-07-13T04:18:14.546Z",
   "requestMethod":"SqsStagerJob",
   "ingestDates":"2018-07-13T04:18:14.571Z",
   "acquisitionDates":"2008-03-11T07:14:29.000Z",
   "missionids":"WV01",
   "dataInfoStatus":200,
   "secondsBeforeQueue":326257982.556,
   "duration":11.986,
   "dataInfoDuration":0.03,
   "bes":"null",
   "indexDuration":0.04,
   "dataInfoMessage":"",
   "entryIds":"0",
   "sourceUri":"",
   "httpStatus":404,
   "indexMessage":"",
   "filenames":"",
   "startTime":"2018-07-13T04:18:02.614Z",
   "indexEndTime":"2018-07-13T04:18:14.586Z",
   "postAvroMetadataStartTime":"2018-07-13T04:18:14.586Z",
   "stageDuration":1.214,
   "downloadEndTime":"2018-07-13T04:18:13.296Z",
   "stageStartTime":"2018-07-13T04:18:13.297Z",
   "totalDurationSinceAcquisition":326257994.600,
   "stageEndTime":"2018-07-13T04:18:14.511Z",
   "downloadMessage":"",
   "indexStatus":200,
   "messageId":"cbd7e477-d181-4c10-90ef-d4ee5ff126d0",
   "stageMessage":"",
   "sensorids":"AA",
   "statusMessage":"Unable to post metadata.  ERROR: HTTP/1.1 404 ",
   "fileTypes":"nitf",
   "downloadDuration":10.677,
   "postAvroMetadataEndTime":"2018-07-13T04:18:14.611Z",
   "dataInfoStartTime":"2018-07-13T04:18:14.516Z",
   "filename":"",
   "postAvroMetadataDuration":0.025,
   "dataInfoEndTime":"2018-07-13T04:18:14.546Z",
   "fileSize":472076970,
   "downloadStartTime":"2018-07-13T04:18:02.619Z",
   "sqsTimestamp":"2018-07-13T04:18:02.556Z",
   "endTime":"2018-07-13T04:18:14.611Z",
   "downloadStatus":200,
   "secondsOnQueue":0.058,
   "imageids":""
}
```

**JSON Queue log**

```
{  
   "approximateNumberOfMessages":"0",
   "approximateNumberOfMessagesDelayed":"0",
   "approximateNumberOfMessagesNotVisible":"0"
}
```

You will see individual times for different stages of the process.  This include download, staging (build overviews histograms), indexing into the tables and posting the avro to another service.  You will see overall time identified by startTime, endTime and duration (in secoonds).   All times are formatted UTC Zulu time using the ISO standard full date format where time is separated by 'T'.  We have also added a separate JSON log for the messages on the queue.
 