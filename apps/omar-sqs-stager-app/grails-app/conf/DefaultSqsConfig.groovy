sqs{
   reader{
      enabled = true
      queue = ""
      waitTimeSeconds = 20
      maxNumberOfMessages = 1
      pollingIntervalSeconds = 10
      destination{
         type: "stdout"

//         http{
//            url = ""
//            field = "message"
//         }   
      }
   }
   stager{
      params{
         buildHistograms         = true
         buildOverviews          = true
         buildHistogramsWithR0   = false
         overviewCompressionType = "NONE"
         overviewType            = "ossim_tiff_box"
      }
      addRaster{
         url                     = ""
      }
   }
}