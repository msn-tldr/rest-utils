package io.confluent.rest.metrics;

import io.confluent.rest.*;
import io.confluent.rest.annotations.PerformanceMetric;
import org.apache.kafka.common.metrics.KafkaMetric;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ErrorHandler;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.glassfish.jersey.server.ServerProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import javax.servlet.RequestDispatcher;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.ProcessingException;
import javax.ws.rs.Produces;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.core.Configurable;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import static io.confluent.rest.metrics.MetricsResourceMethodApplicationListener.HTTP_STATUS_CODE_TAG;
import static io.confluent.rest.metrics.MetricsResourceMethodApplicationListener.HTTP_STATUS_CODE_TEXT;
import static org.junit.jupiter.api.Assertions.*;

public class MetricsResourceMethodApplicationListenerIntegrationTest {

  TestRestConfig config;
  ApplicationWithFilter app;
  private Server server;
  volatile Throwable handledException = null;
  private AtomicInteger counter;

  @BeforeEach
  public void setUp() throws Exception {
    TestMetricsReporter.reset();
    Properties props = new Properties();
    props.setProperty("debug", "false");
    props.put(RestConfig.METRICS_REPORTER_CLASSES_CONFIG, "io.confluent.rest.TestMetricsReporter");
    config = new TestRestConfig(props);
    app = new ApplicationWithFilter(config);
    server = app.createServer();
    server.start();
    counter = new AtomicInteger();
  }

  @AfterEach
  public void tearDown() throws Exception {
    server.stop();
    server.join();
  }

  @Test
  public void testListenerHandlesDispatchErrorsGracefully() {
    // request events do not follow the typical order when an error is raised during dispatch
    // this test ensures we properly handle the case where we might encounter events in the
    // following order.
    //
    // MATCHING_START -> REQUEST_MATCHED -> REQUEST_FILTERED
    //   -> RESOURCE_METHOD_START -> RESOURCE_METHOD_FINISHED -> ON_EXCEPTION -> FINISHED

    // RequestEvent.Type.FINISHED before RequestEvent.Type.RESP_FILTERS_START
    Response response = ClientBuilder.newClient(app.resourceConfig.getConfiguration())
        .target(server.getURI())
        .path("/private/endpoint")
        .request(MediaType.APPLICATION_JSON_TYPE)
        .get();
    assertEquals(500, response.getStatus());
    // ensure the true cause actually bubble up to the error handler
    assertNotNull(handledException);
    Throwable cause = handledException;
    while (!(cause instanceof ProcessingException)) {
      if (cause == cause.getCause()) {
        break;
      }
      cause = cause.getCause();
    }
    assertTrue(cause instanceof ProcessingException);
    assertEquals("Resource Java method invocation error.", cause.getMessage());
  }

  @Test
  public void testSuccessMetrics() {
    int totalRequests = 10;
    IntStream.range(0, totalRequests).forEach((i) -> makeSuccessfulCall());

    // checkpoints ensure that all the assertions are tested
    int totalRequestsCheckpoint = 0;
    int helloRequestsCheckpoint = 0;
    int helloTag1RequestsCheckpoint = 0;
    int helloTag2RequestsCheckpoint = 0;

    for (KafkaMetric metric : TestMetricsReporter.getMetricTimeseries()) {
      switch (metric.metricName().name()) {
        case "request-count": // global metrics
        case "request-total": // global metrics
          assertMetric(metric, totalRequests);
          totalRequestsCheckpoint++;
          break;
        case "hello.request-count": // method metrics
        case "hello.request-total": // method metrics
          if (metric.metricName().tags().containsValue("value1")) {
            assertMetric(metric, (totalRequests + 1) / 3);
            helloTag1RequestsCheckpoint++;
          } else if (metric.metricName().tags().containsValue("value2")) {
            assertMetric(metric, totalRequests / 3);
            helloTag2RequestsCheckpoint++;
          } else if (metric.metricName().tags().isEmpty()) {
            assertMetric(metric, (totalRequests + 2) / 3);
            helloRequestsCheckpoint++;
          }
          break;
      }
    }
    assertEquals(2, totalRequestsCheckpoint);
    assertEquals(2, helloTag1RequestsCheckpoint);
    assertEquals(2, helloTag2RequestsCheckpoint);
    assertEquals(2, helloRequestsCheckpoint);
  }

  @Test
  public void test4xxMetrics() {
    Response response = ClientBuilder.newClient(app.resourceConfig.getConfiguration())
        .target(server.getURI())
        .path("/private/fake")
        .request(MediaType.APPLICATION_JSON_TYPE)
        .get();
    assertEquals(404, response.getStatus());

    ClientBuilder.newClient(app.resourceConfig.getConfiguration())
        .target(server.getURI())
        .path("/private/fake")
        .request(MediaType.APPLICATION_JSON_TYPE)
        .get();

    //checkpoints ensure that all the assertions are tested
    int rateCheckpoint4xx = 0;
    int windowCheckpoint4xx = 0;
    int rateCheckpointNot4xx = 0;
    int windowCheckpointNot4xx = 0;
    int anyErrorRateCheckpoint = 0;
    int anyErrorWindowCheckpoint = 0;

    for (KafkaMetric metric : TestMetricsReporter.getMetricTimeseries()) {
      if (metric.metricName().name().equals("request-error-rate")) {
        assertTrue(metric.measurable().toString().toLowerCase().startsWith("rate"));
        Object metricValue = metric.metricValue();
        assertTrue(metricValue instanceof Double, "Error rate metrics should be measurable");
        double errorRateValue = (double) metricValue;
        if (metric.metricName().tags().getOrDefault(HTTP_STATUS_CODE_TAG, "").equals("4xx")) {
          rateCheckpoint4xx++;
          assertTrue(errorRateValue > 0, "Actual: " + errorRateValue);
        } else if (!metric.metricName().tags().isEmpty()) {
          rateCheckpointNot4xx++;
          assertTrue(errorRateValue == 0.0 || Double.isNaN(errorRateValue),
              String.format("Actual: %f (%s)", errorRateValue, metric.metricName()));
        } else {
          anyErrorRateCheckpoint++;
          //average rate is not consistently above 0 here, so not validating
        }
      }
      if (metric.metricName().name().equals("request-error-count")) {
        assertTrue(metric.measurable().toString().toLowerCase().startsWith("sampledstat"));
        Object metricValue = metric.metricValue();
        assertTrue(metricValue instanceof Double, "Error count metrics should be measurable");
        double errorCountValue = (double) metricValue;
        if (metric.metricName().tags().getOrDefault(HTTP_STATUS_CODE_TAG, "").equals("4xx")) {
          windowCheckpoint4xx++;
          assertTrue(errorCountValue == 2.0, "Actual: " + errorCountValue);
        } else if (!metric.metricName().tags().isEmpty()) {
          windowCheckpointNot4xx++;
          assertTrue(
              errorCountValue == 0.0 || Double.isNaN(errorCountValue),
              String.format("Actual: %f (%s)", errorCountValue, metric.metricName()));
        } else {
          anyErrorWindowCheckpoint++;
          assertTrue(errorCountValue == 2.0, "Count for all errors actual: " + errorCountValue);
        }
      }
    }
    int non4xxCount = HTTP_STATUS_CODE_TEXT.length - 1;
    assertEquals(1, anyErrorRateCheckpoint); //A Single rate metric for the two errors
    assertEquals(1, anyErrorWindowCheckpoint); //A single windowed metric for the two errors
    assertEquals(1, rateCheckpoint4xx); //Single rate metric for the two 4xx errors
    assertEquals(1, windowCheckpoint4xx); ///A single windowed metric for the two 4xx errors
    assertEquals(non4xxCount, rateCheckpointNot4xx); //Metrics for each of unknown, 1xx, 2xx, 3xx, 5xx
    assertEquals(non4xxCount, windowCheckpointNot4xx); //Metrics for each of unknown, 1xx, 2xx, 3xx, 5xx for windowed metrics
  }

  @Test
  public void testException5xxMetrics() {
    int totalRequests = 10;
    IntStream.range(0, totalRequests).forEach((i) -> makeFailedCall());

    int totalCheckpoint = 0;
    int totalCheckpoint5xx = 0;
    int totalCheckpointNon5xx = 0;
    int caughtCheckpoint5xx = 0;
    int caughtCheckpointNon5xx = 0;
    int caughtTag1Checkpoint5xx = 0;
    int caughtTag1CheckpointNon5xx = 0;
    int caughtTag2Checkpoint5xx = 0;
    int caughtTag2CheckpointNon5xx = 0;

    for (KafkaMetric metric : TestMetricsReporter.getMetricTimeseries()) {
      Map<String, String> tags = metric.metricName().tags();
      switch (metric.metricName().name()) {
        case "request-error-count": // global metrics
        case "request-error-total": // global metrics
          if (is5xxError(tags)) {
            assertMetric(metric, totalRequests);
            totalCheckpoint5xx++;
          } else if (tags.containsKey(HTTP_STATUS_CODE_TAG)) {
            assertMetric(metric, 0);
            totalCheckpointNon5xx++;
          } else if (tags.isEmpty()) {
            assertMetric(metric, totalRequests);
            totalCheckpoint++;
          }
          break;
        case "caught.request-error-count": // method metrics
        case "caught.request-error-total": // method metrics
          if (tags.containsValue("value1")) {
            if (is5xxError(tags)) {
              assertMetric(metric, (totalRequests + 1) / 3);
              caughtTag1Checkpoint5xx++;
            } else if (tags.containsKey(HTTP_STATUS_CODE_TAG)) {
              assertMetric(metric, 0);
              caughtTag1CheckpointNon5xx++;
            }
          } else if (tags.containsValue("value2")) {
            if (is5xxError(tags)) {
              assertMetric(metric, totalRequests / 3);
              caughtTag2Checkpoint5xx++;
            } else if (tags.containsKey(HTTP_STATUS_CODE_TAG)) {
              assertMetric(metric, 0);
              caughtTag2CheckpointNon5xx++;
            }
          } else {
            if (is5xxError(tags)) {
              assertMetric(metric, (totalRequests + 2) / 3);
              caughtCheckpoint5xx++;
            } else if (tags.containsKey(HTTP_STATUS_CODE_TAG)) {
              assertMetric(metric, 0);
              caughtCheckpointNon5xx++;
            }
          }
          break;
      }
    }
    int non5xxCount = HTTP_STATUS_CODE_TEXT.length - 1;
    assertEquals(2, totalCheckpoint);
    assertEquals(2, totalCheckpoint5xx);
    assertEquals(non5xxCount * 2, totalCheckpointNon5xx);
    assertEquals(2, caughtCheckpoint5xx);
    assertEquals(non5xxCount * 2, caughtCheckpointNon5xx);
    assertEquals(2, caughtTag1Checkpoint5xx);
    assertEquals(non5xxCount * 2, caughtTag1CheckpointNon5xx);
    assertEquals(2, caughtTag2Checkpoint5xx);
    assertEquals(non5xxCount * 2, caughtTag2CheckpointNon5xx);
  }

  @Test
  public void testMetricReporterConfiguration() {
    ApplicationWithFilter app;
    Properties props = new Properties();

    props.put(RestConfig.METRICS_REPORTER_CONFIG_PREFIX + "prop1", "val1");
    props.put(RestConfig.METRICS_REPORTER_CONFIG_PREFIX + "prop2", "val2");
    props.put(RestConfig.METRICS_REPORTER_CONFIG_PREFIX + "prop3", "override");
    props.put("prop3", "original");
    props.put(RestConfig.METRICS_REPORTER_CLASSES_CONFIG, "io.confluent.rest.TestMetricsReporter");
    props.put("not.prefixed.config", "val3");

    app = new ApplicationWithFilter(new TestRestConfig(props));
    TestMetricsReporter reporter = (TestMetricsReporter) app.getMetrics().reporters().get(0);

    assertTrue(reporter.getConfigs().containsKey("not.prefixed.config"));
    assertTrue(reporter.getConfigs().containsKey("prop1"));
    assertTrue(reporter.getConfigs().containsKey("prop2"));
    assertEquals(reporter.getConfigs().get("prop3"), "override");
  }

  private void makeSuccessfulCall() {
    Response response = ClientBuilder.newClient(app.resourceConfig.getConfiguration())
            .target(server.getURI())
            .path("/public/hello")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .get();
    assertEquals(200, response.getStatus());
  }

  private void makeFailedCall() {
    Response response = ClientBuilder.newClient(app.resourceConfig.getConfiguration())
            .target(server.getURI())
            .path("/public/caught")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .get();
    assertEquals(500, response.getStatus());
  }

  private boolean is5xxError(Map<String, String> tags) {
    return tags.getOrDefault(HTTP_STATUS_CODE_TAG, "").equals("5xx");
  }

  private void assertMetric(KafkaMetric metric, int expectedValue) {
    Object metricValue = metric.metricValue();
    assertTrue(metricValue instanceof Double, "Metrics should be measurable");
    double countValue = (double) metricValue;
    assertEquals(expectedValue, countValue, "Actual: " + countValue);
  }

  private class ApplicationWithFilter extends Application<TestRestConfig> {

    Configurable resourceConfig;

    ApplicationWithFilter(TestRestConfig props) {
      super(props);
    }

    @Override
    public void setupResources(Configurable<?> config, TestRestConfig appConfig) {
      resourceConfig = config;
      config.register(PrivateResource.class);
      config.register(new PublicResource());
      config.register(new Filter());

      // ensures the dispatch error message gets shown in the response
      // as opposed to a generic error page
      config.property(ServerProperties.RESPONSE_SET_STATUS_OVER_SEND_ERROR, true);
    }

    @Override
    protected void configurePostResourceHandling(ServletContextHandler context) {
      context.setErrorHandler(new ErrorHandler() {
        @Override
        public void handle(
            String target,
            Request baseRequest,
            HttpServletRequest request,
            HttpServletResponse response
        ) throws IOException, ServletException {
          handledException = (Throwable) request.getAttribute(RequestDispatcher.ERROR_EXCEPTION);
          super.handle(target, baseRequest, request, response);
        }
      });
    }

  }

  @Produces(MediaType.APPLICATION_JSON)
  @Path("/private")
  private static class PrivateResource {

    @GET
    @Path("/endpoint")
    public Void notAccessible() {
      return null;
    }
  }

  @Produces(MediaType.APPLICATION_JSON)
  @Path("/public/")
  public static class PublicResource {

    @GET
    @Path("/caught")
    @PerformanceMetric("caught")
    public Void caught() {
      throw new RuntimeException("cyrus");
    }

    @GET
    @Path("/hello")
    @PerformanceMetric("hello")
    public String hello() {
      return "hello";
    }
  }

  public class Filter implements ContainerRequestFilter {
    private final String[] tags = new String[]{"", "value1", "value2"};
    @Override
    public void filter(ContainerRequestContext context) {
      Map<String, String> maps = new HashMap<>();
      String value = tags[counter.getAndIncrement() % tags.length];
      if (value != null && value.length() > 0) {
        maps.put("tag", value);
      }
      context.setProperty(MetricsResourceMethodApplicationListener.REQUEST_TAGS_PROP_KEY, maps);
    }
  }
}
