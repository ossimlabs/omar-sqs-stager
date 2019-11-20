package sqs.app

import grails.testing.services.ServiceUnitTest
import omar.avro.AvroService
import omar.core.HttpStatus

import spock.lang.Specification

class SqsStagerServiceSpec extends Specification implements ServiceUnitTest<SqsStagerService> {

    /**
     * We need to use dowWithSpring to add references
     * to external dependencies (services) that SqsStagerService
     * uses
     */
    Closure doWithSpring() {{ ->
        avroService(AvroService)
    }}

    def setup() {
        AvroService avroService
    }

    def cleanup() {
    }

    void 'checkDownloadBlackList method with full imageId of "SICD 123456789" == true' ()
    {
        expect:
            service.checkDownloadBlackList('SICD 123456789') == true
    }

    void 'checkDownloadBlackList method with full imageId of "SIDD: 14SEP15TS0107001_100021_SL0023L_25N121E_001X___SVV_0101_OBS_IMAG" == true' ()
    {
        expect:
        service.checkDownloadBlackList('SIDD: 14SEP15TS0107001_100021_SL0023L_25N121E_001X___SVV_0101_OBS_IMAG') == true
    }

    void 'checkDownloadBlackList method with lower case imageId partial of "sicd" should == true' ()
    {
        expect:
            service.checkDownloadBlackList('sicd') == true
    }

    void 'checkDownloadBlackList method with lower case imageId partial of "bar" should == true' ()
    {
        expect:
            service.checkDownloadBlackList('bar') == true
    }

    void 'checkDownloadBlackList method with mixed case imageId partial of "SicD" should == true' ()
    {
        expect:
            service.checkDownloadBlackList('SicD') == true
    }

    void 'checkDownloadBlackList method with non-matching imageId partial of "sick" should == false' ()
    {
        expect:
            service.checkDownloadBlackList('sick') == false
    }

    void 'checkDownloadBlackList method with non-matching/mixed case imageId partial of "sICk" should == false' ()
    {
        expect:
        service.checkDownloadBlackList('sick') == false
    }

    void 'checkDownloadBlackList method with non-matching imageId partial and random characters with whitespace should == true' ()
    {
        expect:
        service.checkDownloadBlackList('sicd ,%') == true
    }

    void 'checkDownloadBlackList method with mixed case imageId partial of "16mAy" should == true' () {
        expect:
            service.checkDownloadBlackList('16mAy') == true

    }

    void 'checkDownloadBlackList method with passing a null imageId should == false' () {
        expect:
            service.checkDownloadBlackList(null) == false

    }

    void 'Testing downloadFile method with imageId: 16MAY02111607-P1BS-055998375010_01_P014 that should return message (Black listed)' () {
        setup:
            def avroMessage = '{"Message":"{\\"abstract\\":\\"SICD                            ,                                          blah\\",\\"actualBitCountPerPixel\\":0,\\"areaTargetId\\":\\"\\",\\"bENumber\\":\\"\\",\\"bandCountQuantity\\":0,\\"boundingPolygonTypeCode\\":\\"\\",\\"centroidCoordinates\\":\\"\\",\\"circularError\\":\\"\\",\\"cloudCoverPercentage\\":\\"\\",\\"collectionModeCode\\":\\"\\",\\"countryCode\\":[\\"\\"],\\"dataType\\":\\"SICD9999\\",\\"eDHDataSet\\":\\"\\",\\"fileFormatName\\":\\"\\",\\"fileName\\":\\"\\",\\"fileVersion\\":\\"\\",\\"footprintCoordinates\\":\\"\\",\\"footprintGeometry\\":\\"\\",\\"grazingAngle\\":\\"\\",\\"iCEDHAuthorizationReference\\":\\"\\",\\"iCEDHClassification\\":\\"\\",\\"iCEDHClassificationReason\\":\\"\\",\\"iCEDHClassifiedBy\\":\\"\\",\\"iCEDHDESVersion\\":\\"\\",\\"iCEDHDataItemCreateDateTime\\":\\"\\",\\"iCEDHDeclassExceptions\\":\\"\\",\\"iCEDHDerivedFrom\\":\\"\\",\\"iCEDHDisseminationControls\\":\\"\\",\\"iCEDHIdentifier\\":\\"\\",\\"iCEDHLAC\\":\\"\\",\\"iCEDHMAF\\":\\"\\",\\"iCEDHOwnerProducer\\":\\"\\",\\"iCEDHReleasableTo\\":\\"\\",\\"iCEDHResponsibleEntityCountry\\":\\"\\",\\"iCEDHResponsibleEntityOrganization\\":\\"\\",\\"iCEDHResponsibleEntitySuborganization\\":\\"\\",\\"iCEDHSciControls\\":\\"\\",\\"iGEOLO\\":\\"\\",\\"imageCenterPoint2D\\":\\"\\",\\"imageId\\":\\"SICD  16MAY02111607-P1BS-055998375010_01_P014\\",\\"imagePolarization\\":\\"\\",\\"imageQualityRatingScale\\":0,\\"imageRepresentation\\":\\"\\",\\"imageRollAngle\\":0,\\"imageTypeCode\\":\\"\\",\\"jpegReducedResolution\\":\\"\\",\\"keyword\\":\\"\\",\\"keyword10\\":\\"\\",\\"keyword2\\":\\"\\",\\"keyword3\\":\\"\\",\\"keyword4\\":\\"\\",\\"keyword5\\":\\"\\",\\"keyword6\\":\\"\\",\\"keyword7\\":\\"\\",\\"keyword8\\":\\"\\",\\"keyword9\\":\\"\\",\\"lIMDISCODE\\":\\"\\",\\"linearerror\\":0.0,\\"mBREAST\\":0,\\"mBRNORTH\\":0,\\"mBRSOUTH\\":0,\\"mBRWEST\\":0,\\"meanGroundSpacingDistance\\":0,\\"missionID\\":\\"\\",\\"oMARURI\\":\\"\\",\\"obliquityAngle\\":0,\\"observationDateTime\\":\\"2016-05-02T00:00:00.000Z\\",\\"originationStationId\\":\\"\\",\\"platformName\\":\\"\\",\\"processingLevel\\":\\"\\",\\"producerClass\\":\\"\\",\\"producerCode\\":\\"\\",\\"productCreationDate\\":\\"\\",\\"rapidPositioningCapabilityIndicator\\":true,\\"referenceImageTypeText\\":\\"\\",\\"replayDesignator\\":\\"\\",\\"resourceCategoryName\\":\\"\\",\\"s3URIAvro\\":\\"\\",\\"s3URIEpjeNitf\\":\\"\\",\\"s3URINitf\\":\\"\\",\\"s3URIOverviewJpeg\\":\\"\\",\\"s3URIThumbnailJpeg\\":\\"\\",\\"sensorName\\":\\"\\",\\"sizeQuantity\\":0,\\"spectralCoverage\\":\\"0\\",\\"spectralCoverageTypeCode\\":\\"\\",\\"stereoIdText\\":\\"\\",\\"sunAzimuth\\":0,\\"sunElevation\\":0,\\"sunElevationDim\\":0,\\"targetAlternateID\\":\\"\\",\\"targetName\\":\\"\\",\\"titleText\\":\\"\\",\\"uRL\\":\\"https://s3.amazonaws.com/o2-test-data/Standard_test_imagery_set/Paris/16MAY02111607-P1BS-055998375010_01_P014.TIF\\",\\"version\\":\\"\\",\\"xAxisDimension\\":0,\\"yAxisDimension\\":0,\\"namespace\\":\\"\\"}"}'
        expect: 'A black listed message return the proper message'
            service.downloadFile(avroMessage).message == "Image type not allowed to be downloaded (Black listed)"
    }


    void 'Testing downloadFile method with imageId: 16MAY02111607-P1BS-055998375010_01_P014 that should return METHOD_NOT_ALLOWED ' () {
        setup:
        // Message contains an black listed item in the abstract field, and should not be allowed to download
        def avroMessage = '{"Message":"{\\"abstract\\":\\"SICD                            ,                                          blah\\",\\"actualBitCountPerPixel\\":0,\\"areaTargetId\\":\\"\\",\\"bENumber\\":\\"\\",\\"bandCountQuantity\\":0,\\"boundingPolygonTypeCode\\":\\"\\",\\"centroidCoordinates\\":\\"\\",\\"circularError\\":\\"\\",\\"cloudCoverPercentage\\":\\"\\",\\"collectionModeCode\\":\\"\\",\\"countryCode\\":[\\"\\"],\\"dataType\\":\\"SICD9999\\",\\"eDHDataSet\\":\\"\\",\\"fileFormatName\\":\\"\\",\\"fileName\\":\\"\\",\\"fileVersion\\":\\"\\",\\"footprintCoordinates\\":\\"\\",\\"footprintGeometry\\":\\"\\",\\"grazingAngle\\":\\"\\",\\"iCEDHAuthorizationReference\\":\\"\\",\\"iCEDHClassification\\":\\"\\",\\"iCEDHClassificationReason\\":\\"\\",\\"iCEDHClassifiedBy\\":\\"\\",\\"iCEDHDESVersion\\":\\"\\",\\"iCEDHDataItemCreateDateTime\\":\\"\\",\\"iCEDHDeclassExceptions\\":\\"\\",\\"iCEDHDerivedFrom\\":\\"\\",\\"iCEDHDisseminationControls\\":\\"\\",\\"iCEDHIdentifier\\":\\"\\",\\"iCEDHLAC\\":\\"\\",\\"iCEDHMAF\\":\\"\\",\\"iCEDHOwnerProducer\\":\\"\\",\\"iCEDHReleasableTo\\":\\"\\",\\"iCEDHResponsibleEntityCountry\\":\\"\\",\\"iCEDHResponsibleEntityOrganization\\":\\"\\",\\"iCEDHResponsibleEntitySuborganization\\":\\"\\",\\"iCEDHSciControls\\":\\"\\",\\"iGEOLO\\":\\"\\",\\"imageCenterPoint2D\\":\\"\\",\\"imageId\\":\\"SICD 16MAY02111607-P1BS-055998375010_01_P014\\",\\"imagePolarization\\":\\"\\",\\"imageQualityRatingScale\\":0,\\"imageRepresentation\\":\\"\\",\\"imageRollAngle\\":0,\\"imageTypeCode\\":\\"\\",\\"jpegReducedResolution\\":\\"\\",\\"keyword\\":\\"\\",\\"keyword10\\":\\"\\",\\"keyword2\\":\\"\\",\\"keyword3\\":\\"\\",\\"keyword4\\":\\"\\",\\"keyword5\\":\\"\\",\\"keyword6\\":\\"\\",\\"keyword7\\":\\"\\",\\"keyword8\\":\\"\\",\\"keyword9\\":\\"\\",\\"lIMDISCODE\\":\\"\\",\\"linearerror\\":0.0,\\"mBREAST\\":0,\\"mBRNORTH\\":0,\\"mBRSOUTH\\":0,\\"mBRWEST\\":0,\\"meanGroundSpacingDistance\\":0,\\"missionID\\":\\"\\",\\"oMARURI\\":\\"\\",\\"obliquityAngle\\":0,\\"observationDateTime\\":\\"2016-05-02T00:00:00.000Z\\",\\"originationStationId\\":\\"\\",\\"platformName\\":\\"\\",\\"processingLevel\\":\\"\\",\\"producerClass\\":\\"\\",\\"producerCode\\":\\"\\",\\"productCreationDate\\":\\"\\",\\"rapidPositioningCapabilityIndicator\\":true,\\"referenceImageTypeText\\":\\"\\",\\"replayDesignator\\":\\"\\",\\"resourceCategoryName\\":\\"\\",\\"s3URIAvro\\":\\"\\",\\"s3URIEpjeNitf\\":\\"\\",\\"s3URINitf\\":\\"\\",\\"s3URIOverviewJpeg\\":\\"\\",\\"s3URIThumbnailJpeg\\":\\"\\",\\"sensorName\\":\\"\\",\\"sizeQuantity\\":0,\\"spectralCoverage\\":\\"0\\",\\"spectralCoverageTypeCode\\":\\"\\",\\"stereoIdText\\":\\"\\",\\"sunAzimuth\\":0,\\"sunElevation\\":0,\\"sunElevationDim\\":0,\\"targetAlternateID\\":\\"\\",\\"targetName\\":\\"\\",\\"titleText\\":\\"\\",\\"uRL\\":\\"https://s3.amazonaws.com/o2-test-data/Standard_test_imagery_set/Paris/16MAY02111607-P1BS-055998375010_01_P014.TIF\\",\\"version\\":\\"\\",\\"xAxisDimension\\":0,\\"yAxisDimension\\":0,\\"namespace\\":\\"\\"}"}'
        expect:
            service.downloadFile(avroMessage).status == HttpStatus.METHOD_NOT_ALLOWED
    }

}