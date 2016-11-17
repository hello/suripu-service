package com.hello.suripu.service.models;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import com.hello.suripu.api.audio.SimpleMatrixProtos;
import junit.framework.TestCase;
import org.junit.Test;

import java.io.IOException;

/**
 * Created by benjo on 11/17/16.
 */
public class SimpeMatrixJsonTest {

    @Test
    public void testProtobufToJson() throws IOException {
        byte [] bytes1 = {0x00,0x01,0x02};
        byte [] bytes2 = {0x03,0x04,0x05,0x06};

        final SimpleMatrixProtos.SimpleMatrix protbuf =
                SimpleMatrixProtos.SimpleMatrix.newBuilder()
                .setDataType(SimpleMatrixProtos.SimpleMatrixDataType.SINT8)
                .addPayload(ByteString.copyFrom(bytes1))
                .addPayload(ByteString.copyFrom(bytes2))
                .setId("foobars42")
                .setDeviceId("sense")
                .setNumCols(40)
                .build();


        final SimpleMatrix simpleMatrixJsonModel = SimpleMatrix.createFromProtobuf(protbuf,42l);

        TestCase.assertEquals(simpleMatrixJsonModel.dataType,Integer.valueOf(0));
        TestCase.assertEquals(simpleMatrixJsonModel.numCols,Integer.valueOf(40));
        TestCase.assertEquals(simpleMatrixJsonModel.id,"foobars42");
        TestCase.assertEquals(simpleMatrixJsonModel.payload,"AAECAwQFBg==");

        final ObjectMapper objectMapper = new ObjectMapper();

        final String  json  = objectMapper.writeValueAsString(simpleMatrixJsonModel);

        final SimpleMatrix decoded = objectMapper.readValue(json,SimpleMatrix.class);

        TestCase.assertEquals(decoded.dataType,Integer.valueOf(0));
        TestCase.assertEquals(decoded.numCols,Integer.valueOf(40));
        TestCase.assertEquals(decoded.id,"foobars42");
        TestCase.assertEquals(decoded.payload,"AAECAwQFBg==");

    }

}
