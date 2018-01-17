# OMAR SQS STAGER

omar-sqs-stager pulls avro formatted messages off an SQS queue and will download the referenced file and cache locally in a cache directory and stage and index the file into an omar database.  The omar-sqs-stager service will log as a final result a JSON formatted string of the form:

**JSON log:**

```
{
   "stageStatus":200,
   "indexStartTime":"2018-01-17T13:04:54.020Z",
   "requestMethod":"SqsStagerJob",
   "ingestDates":"2018-01-17T13:04:54.043Z",
   "acquisitionDates":"2003-02-05T15:10:53.000Z",
   "missionids":"",
   "dataInfoStatus":200,
   "duration":6.895,
   "dataInfoDuration":0.035,
   "indexDuration":0.038,
   "dataInfoMessage":"",
   "entryIds":"0",
   "sourceUri":"",
   "indexMessage":"",
   "filenames":"",
   "startTime":"2018-01-17T13:04:47.151Z",
   "indexEndTime":"2018-01-17T13:04:54.058Z",
   "postAvroMetadataStartTime":"2018-01-17T13:04:54.069Z",
   "stageDuration":2.086,
   "downloadEndTime":"2018-01-17T13:04:51.889Z",
   "stageStartTime":"2018-01-17T13:04:51.890Z",
   "stageEndTime":"2018-01-17T13:04:53.976Z",
   "downloadMessage":"",
   "indexStatus":200,
   "messageId":"2fad62be-72fd-421f-9247-a7f0cb1d166c",
   "stageMessage":"",
   "sensorids":"null",
   "fileTypes":"nitf",
   "downloadDuration":4.729,
   "postAvroMetadataEndTime":"2018-01-17T13:04:54.076Z",
   "dataInfoStartTime":"2018-01-17T13:04:53.984Z",
   "filename":"",
   "postAvroMetadataDuration":0.007,
   "dataInfoEndTime":"2018-01-17T13:04:54.019Z",
   "downloadStartTime":"2018-01-17T13:04:47.160Z",
   "endTime":"2018-01-17T13:04:54.076Z",
   "downloadStatus":200,
   "imageids":"0000000000"
}
```

You will see individual times for different stages of the process.  This include download, staging (build overviews histograms), indexing into the tables and posting the avro to another service.  You will see overall time identified by startTime, endTime and duration (in secoonds).   All times are formatted UTC Zulu time using the ISO standard full date format where time is separated by 'T'.
 