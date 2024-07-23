package com.github.magicalmuggle;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.ssl.SSLContextBuilder;
import org.apache.http.ssl.SSLContexts;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;

public class ElasticsearchUtil {
    private static final String USER_NAME = "elastic";
    private static final String PASSWORD = "your-password";
    private static final String FILE_PATH_OF_CA_CERTIFICATE = "./http_ca.crt";

    private ElasticsearchUtil() {
    }

    private static synchronized ElasticsearchClient makeHttpsConnection(
            String userName, String password, String filePathOfCaCertificate) throws
            CertificateException, IOException, NoSuchAlgorithmException, KeyStoreException, KeyManagementException {
        final CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
        credentialsProvider.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(userName, password));

        Path caCertificatePath = Paths.get(filePathOfCaCertificate);
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        Certificate trustedCa;
        try (InputStream is = Files.newInputStream(caCertificatePath)) {
            trustedCa = factory.generateCertificate(is);
        }
        KeyStore trustStore = KeyStore.getInstance("pkcs12");
        trustStore.load(null, null);
        trustStore.setCertificateEntry("ca", trustedCa);
        SSLContextBuilder sslContextBuilder = SSLContexts.custom().loadTrustMaterial(trustStore, null);
        final SSLContext sslContext = sslContextBuilder.build();

        RestClientBuilder builder = RestClient.builder(new HttpHost("localhost", 9200, "https"))
                .setHttpClientConfigCallback(httpClientBuilder -> httpClientBuilder.setSSLContext(sslContext)
                        .setDefaultCredentialsProvider(credentialsProvider));

        RestClient restClient = builder.build();

        // Create the transport with a Jackson mapper
        JacksonJsonpMapper mapper = new JacksonJsonpMapper();
        mapper.objectMapper().registerModule(new JavaTimeModule());
        ElasticsearchTransport transport = new RestClientTransport(
                restClient, mapper);

        return new ElasticsearchClient(transport);
    }

    private static ElasticsearchClient makeHttpsConnectionSafely(
            String userName, String password, String filePathOfCaCertificate) {
        try {
            return makeHttpsConnection(userName, password, filePathOfCaCertificate);
        } catch (CertificateException | IOException | NoSuchAlgorithmException | KeyStoreException |
                 KeyManagementException e) {
            throw new RuntimeException(e);
        }
    }

    public static ElasticsearchClient getElasticsearchClient() {
        return makeHttpsConnectionSafely(USER_NAME, PASSWORD, FILE_PATH_OF_CA_CERTIFICATE);
    }
}
