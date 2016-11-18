package com.hello.suripu.service.models;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.hello.suripu.api.audio.SimpleMatrixProtos;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;

/**
 * Created by benjo on 11/17/16.
 */
public class SimpleMatrix {
    /*
         ON PURPOSE THERE IS NO DEVICE ID
         THIS IS TO PRESERVE ANONYMITY OF AUDIO FEATURES
     */

    public static SimpleMatrix createFromProtobuf(SimpleMatrixProtos.SimpleMatrix protobuf, final long timestamp) throws IOException {

        String id = "";
        if (protobuf.hasId()) {
            id = protobuf.getId();
        }

        Integer numCols = 0;
        if (protobuf.hasNumCols()) {
            numCols = protobuf.getNumCols();
        }

        Integer dataType = 0;
        if (protobuf.hasDataType()) {
            switch (protobuf.getDataType()) {

                case SINT8:
                    dataType = protobuf.getDataType().getNumber();
                    break;
            }
        }

        final ByteArrayOutputStream byteStream = new ByteArrayOutputStream();


        for (int i = 0; i < protobuf.getPayloadCount(); i++) {
            byteStream.write(protobuf.getPayload(i).toByteArray());
        }

        return new SimpleMatrix(id,numCols,dataType,timestamp,Base64.getEncoder().encodeToString(byteStream.toByteArray()));

    }

    @JsonCreator
    public SimpleMatrix(
            @JsonProperty("id") String id,
            @JsonProperty("num_cols") Integer numCols,
            @JsonProperty("data_type") Integer dataType,
            @JsonProperty("timestamp_utc")  Long timestamp,
            @JsonProperty("payload") final String payload) {
        this.id = id;
        this.numCols = numCols;
        this.dataType = dataType;
        this.timestamp = timestamp;
        this.payload = payload;
    }


    @JsonProperty("id")
    final public String id;

    @JsonProperty("num_cols")
    final public Integer numCols;

    @JsonProperty("data_type")
    final public Integer dataType;

    @JsonProperty("timestamp_utc")
    final public Long timestamp;

    //base64 binary data
    @JsonProperty("payload")
    final public String payload;



}
