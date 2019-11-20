package omar.sqs.stager.app
import grails.validation.Validateable
import groovy.transform.ToString

@ToString( includeNames = true )
class SqsStagerCommand implements Validateable
{
   Boolean recurseFlag
   static contraints = {
      recurseFlag nullable: true
   }
   HashMap toMap()
   {
      [recurseFlag: recurseFlag]
   }
   String toUrlQuery(Boolean encode=true)
   {
      if(encode)
      {
         toMap().collect(){k,v-> "${k}=${java.net.URLEncoder.encode(v.toString(), 'UTF-8')}"}.join("&")
      }
      else
      {
         toMap().collect(){k,v-> "${k}=${v}"}.join("&")
      }
   }
}
