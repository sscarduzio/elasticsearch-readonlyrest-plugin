package tech.beshu.ror.es.request;

import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.common.io.stream.ByteBufferStreamInput;
import org.elasticsearch.common.io.stream.BytesStreamOutput;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.ByteBuffer;

public class CLCast {

  public static <T> T castObj(Object o) throws IOException, ClassNotFoundException {
    if (o != null) {
      ByteArrayOutputStream baous = new ByteArrayOutputStream();
      {
        ObjectOutputStream oos = new ObjectOutputStream(baous);
        try {
          oos.writeObject(o);
        } finally {
          try {
            oos.close();
          } catch (Exception e) {
          }
        }
      }

      byte[] bb = baous.toByteArray();
      if (bb != null && bb.length > 0) {
        ByteArrayInputStream bais = new ByteArrayInputStream(bb);
        ObjectInputStream ois = new ObjectInputStream(bais);
        T res = (T) ois.readObject();
        return res;
      }
    }
    return null;
  }

  public static <T extends ActionRequest> T cast(ActionRequest ar, T newAr) {
    BytesStreamOutput bso = new BytesStreamOutput();
    try {
      ar.writeTo(bso);
      newAr.readFrom(new ByteBufferStreamInput(ByteBuffer.wrap(bso.bytes().toBytesRef().bytes)));
      return newAr;
    } catch (IOException e) {
      try {
        bso.close();
      } catch (Exception ignored) {
      }
      throw new RuntimeException(e);
    }
  }
}

