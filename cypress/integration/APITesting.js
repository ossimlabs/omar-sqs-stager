let json = require('../../testParameters.json');
let tests = Object.keys(json.tests);
let innerJson, method, endpoint, query, good, parameters, request, keys;

describe('Automated tests for the omar-sqs-stager API endpoints', () => {
    tests.forEach((test) => {
        innerJson = json.tests[test];
        method = innerJson["method"];
        endpoint = innerJson["endpoint"];
        query = innerJson["in"] === "query";
        good = innerJson["expected"] === "good";
        parameters = Object.keys(innerJson["parameters"]);
        if(query && good) {
            request = "?"
            parameters.forEach((parameter) => {
                request = request + parameter + "=" + innerJson.parameters[parameter] + "&";
            })
            request = request.substring(0, request.length - 1);
            it(`Should test 200 code for ${test} test values`, () => {
                cy.request({method: method, url: endpoint + request})
                    .then((response) => {
                        expect(response.status).to.eq(200)
                    })
            })
            it(`Should test response header for ${test}`, () => {
                cy.request({method: method, url: endpoint + request})
                    .then((response) => {
                        expect(response).to.have.property("headers")
                    })
            })
        }
        else if(query) {
            request = "?"
            parameters.forEach((parameter) => {
                request = request + parameter + "=" + innerJson.parameters[parameter] + "&";
            })
            request = request.substring(0, request.length - 1);
            it(`Should test for a not 200 code for ${test} test values`, () => {
                cy.request({method: method, url: endpoint + request})
                    .then((response) => {
                        expect(response.status).to.not.eq(200)
                })
            })
        }
        else if(good) {
            it(`Should test 200 code for ${test} test values`, () => {
                cy.request({method: method, url: endpoint, body: innerJson.parameters["body"]})
                    .then((response) => {
                        expect(response.status).to.eq(200)
                })
            })
            it(`Should test response header for ${test}`, () => {
                cy.request({method: method, url: endpoint, body: innerJson.parameters["body"]})
                    .then((response) => {
                        expect(response).to.have.property("headers")
                })
            })
        }
    })
})