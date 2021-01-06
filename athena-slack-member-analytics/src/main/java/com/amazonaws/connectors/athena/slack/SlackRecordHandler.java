/*-
 * #%L
 * athena-slack-member-analytics
 * %%
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * %%
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this
 * software and associated documentation files (the "Software"), to deal in the Software
 * without restriction, including without limitation the rights to use, copy, modify,
 * merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so.
 * %%
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
 * INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A
 * PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
 * OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package com.amazonaws.connectors.athena.slack;

import com.amazonaws.athena.connector.lambda.QueryStatusChecker;
import com.amazonaws.athena.connector.lambda.data.Block;
import com.amazonaws.athena.connector.lambda.data.BlockSpiller;
import com.amazonaws.athena.connector.lambda.data.writers.GeneratedRowWriter;
import com.amazonaws.athena.connector.lambda.domain.Split;
import com.amazonaws.athena.connector.lambda.handlers.RecordHandler;
import com.amazonaws.athena.connector.lambda.records.ReadRecordsRequest;
import com.amazonaws.connectors.athena.slack.util.SlackHttpUtility;
import com.amazonaws.connectors.athena.slack.util.SlackSchemaUtility;
import com.amazonaws.services.athena.AmazonAthena;
import com.amazonaws.services.athena.AmazonAthenaClientBuilder;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.secretsmanager.AWSSecretsManager;
import com.amazonaws.services.secretsmanager.AWSSecretsManagerClientBuilder;
import org.apache.arrow.util.VisibleForTesting;
import org.apache.http.client.utils.URIBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.BufferedReader;
import java.io.IOException;
import java.util.HashMap;
import org.json.JSONObject;

/**
 * This class is part of an tutorial that will walk you through how to build a connector for your
 * custom data source. The README for this module (athena-example) will guide you through preparing
 * your development environment, modifying this example RecordHandler, building, deploying, and then
 * using your new source in an Athena query.
 * <p>
 * More specifically, this class is responsible for providing Athena with actual rows level data from your source. Athena
 * will call readWithConstraint(...) on this class for each 'Split' you generated in ExampleMetadataHandler.
 * <p>
 * For more examples, please see the other connectors in this repository (e.g. athena-cloudwatch, athena-docdb, etc...)
 */
public class SlackRecordHandler extends RecordHandler
{
    private static final Logger logger = LoggerFactory.getLogger(SlackRecordHandler.class);

    /**
     * used to aid in debugging. Athena will use this name in conjunction with your catalog id
     * to correlate relevant query errors.
     */
    private static final String SOURCE_TYPE = "slackanalytics";

    public SlackRecordHandler()
    {
        this(AmazonS3ClientBuilder.defaultClient(), AWSSecretsManagerClientBuilder.defaultClient(), AmazonAthenaClientBuilder.defaultClient());
    }

    @VisibleForTesting
    protected SlackRecordHandler(AmazonS3 amazonS3, AWSSecretsManager secretsManager, AmazonAthena amazonAthena)
    {
        super(amazonS3, secretsManager, amazonAthena, SOURCE_TYPE);
    }

    /**
     * Used to read the row data associated with the provided Split.
     *
     * @param spiller A BlockSpiller that should be used to write the row data associated with this Split.
     * The BlockSpiller automatically handles chunking the response, encrypting, and spilling to S3.
     * @param recordsRequest Details of the read request, including:
     * 1. The Split
     * 2. The Catalog, Database, and Table the read request is for.
     * 3. The filtering predicate (if any)
     * 4. The columns required for projection.
     * @param queryStatusChecker A QueryStatusChecker that you can use to stop doing work for a query that has already terminated
     * @throws IOException
     * @note Avoid writing >10 rows per-call to BlockSpiller.writeRow(...) because this will limit the BlockSpiller's
     * ability to control Block size. The resulting increase in Block size may cause failures and reduced performance.
     */
    @Override
    protected void readWithConstraint(BlockSpiller spiller, ReadRecordsRequest recordsRequest, QueryStatusChecker queryStatusChecker)
            throws Exception{
        logger.info("readWithConstraint: enter");

        try {
            Split split = recordsRequest.getSplit();
            String splitDateVal = split.getProperty("date");
            String splitAuthToken = split.getProperty("authToken");
            
            //Retrieving schema elements 
            String tableName = recordsRequest.getTableName().getTableName();
            logger.info("readWithConstraint: Reading schema for table " + tableName);
            GeneratedRowWriter.RowWriterBuilder builder = SlackSchemaUtility.getRowWriterBuilder(recordsRequest, tableName);
            GeneratedRowWriter rowWriter = builder.build();
            
            //Get records for date
            String baseURL = System.getenv("data_endpoint");
            URIBuilder requestURI  = new URIBuilder(baseURL);
            requestURI.addParameter("date", splitDateVal);
            requestURI.addParameter("type", "member");
            HashMap<String, String> headers = new HashMap<String, String>();
            headers.put("Authorization", "Bearer " + splitAuthToken);
            BufferedReader reader = SlackHttpUtility.getData(requestURI, headers);
            
            //Process each line.
            String line;
            while (reader != null && (line = reader.readLine()) != null) { // Read line by line
                logger.debug("readWithConstraint: Line - " + line);
                JSONObject record = new JSONObject(line);
                spiller.writeRows((Block block, int rowNum) -> rowWriter.writeRow(block, rowNum, record) ? 1 : 0);
            
            }
        } catch (Exception e){
            throw new RuntimeException("readWithConstraint: Error - " + e.getMessage());
        } finally {
            SlackHttpUtility.disconnect();
        }
        
        logger.info("readWithConstraint: exit");
    }
}
