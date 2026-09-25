package test;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.time.Instant;
public class Test {
    public static void main(String[] args) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        System.out.println("No timestamps: " + mapper.writeValueAsString(Instant.now()));
        
        mapper.enable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        System.out.println("With timestamps: " + mapper.writeValueAsString(Instant.now()));
    }
}
