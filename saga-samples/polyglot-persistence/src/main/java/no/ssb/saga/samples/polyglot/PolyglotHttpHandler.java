package no.ssb.saga.samples.polyglot;

import io.undertow.io.Receiver;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;
import no.ssb.concurrent.futureselector.SelectableThreadPoolExectutor;
import no.ssb.saga.api.Saga;
import no.ssb.saga.execution.SagaExecution;
import no.ssb.saga.execution.SagaHandoffControl;
import no.ssb.saga.execution.adapter.AdapterLoader;
import no.ssb.saga.execution.sagalog.SagaLog;
import no.ssb.saga.execution.sagalog.SagaLogEntry;
import no.ssb.saga.samples.polyglot.adapter.PublishToPubSub;
import no.ssb.saga.samples.polyglot.adapter.WriteToGraph;
import no.ssb.saga.samples.polyglot.adapter.WriteToObjectStore;
import no.ssb.saga.samples.polyglot.adapter.WriteToRDBMS;
import no.ssb.saga.serialization.SagaSerializer;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class PolyglotHttpHandler implements HttpHandler {
    private final SelectableThreadPoolExectutor executorService;
    private final SagaLog sagaLog;
    private final AdapterLoader adapterLoader;

    public PolyglotHttpHandler(SelectableThreadPoolExectutor executorService, SagaLog sagaLog) {
        this.executorService = executorService;
        this.sagaLog = sagaLog;

        /*
         * Register all adapters/drivers that will be used in sagas.
         */
        adapterLoader = new AdapterLoader()
                .register(new WriteToRDBMS(JSONObject.class))
                .register(new WriteToGraph(JSONObject.class))
                .register(new WriteToObjectStore(JSONObject.class))
                .register(new PublishToPubSub(JSONObject.class));
    }

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {
        if (exchange.isInIoThread()) {
            exchange.dispatch(this);
            return;
        }

        if (exchange.getRequestMethod().equalToString("get")) {
            return;
        } else if (exchange.getRequestMethod().equalToString("put")) {
            exchange.getRequestReceiver().receiveFullBytes(PUTCallback());
            return;
        }

        exchange.setStatusCode(400);
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/plain");
        exchange.getResponseSender().send("Unsupported method: " + exchange.getRequestMethod());
    }

    private Receiver.FullBytesCallback PUTCallback() {
        return (exchange, buf) -> {


            /*
             * Create Saga that will first write to 3 different persistence-technologies
             * in parallel, then publish an event to a pub-sub technology.
             */
            Saga polyglotSaga = Saga.start("Polyglot Saga")
                    .linkTo("rdbms", "graph", "objectstore")
                    .id("rdbms").adapter(WriteToRDBMS.NAME).linkTo("pubsub")
                    .id("graph").adapter(WriteToGraph.NAME).linkTo("pubsub")
                    .id("objectstore").adapter(WriteToObjectStore.NAME).linkTo("pubsub")
                    .id("pubsub").adapter(PublishToPubSub.NAME).linkToEnd()
                    .end();


            /*
             * Prepare Saga input
             */
            JSONObject inputRoot = new JSONObject();
            inputRoot.put("resource-path", exchange.getRequestPath());
            inputRoot.put("data", new String(buf, StandardCharsets.UTF_8));

            /*
             * Execute Saga
             */
            String executionId = UUID.randomUUID().toString();
            SagaExecution sagaExecution = new SagaExecution(sagaLog, executorService, polyglotSaga, adapterLoader);
            long handoffTime;
            long executionTime;
            try {
                long executionStartTime = System.currentTimeMillis();
                SagaHandoffControl handoffControl = sagaExecution.executeSaga(executionId, inputRoot, false);
                handoffControl.getHandoffFuture().get(30, TimeUnit.SECONDS); // wait for saga handoff
                handoffTime = System.currentTimeMillis() - executionStartTime;
                handoffControl.getCompletionFuture().get(5, TimeUnit.MINUTES); // wait for saga completion
                executionTime = System.currentTimeMillis() - executionStartTime;
            } catch (InterruptedException | ExecutionException | TimeoutException e) {
                throw new RuntimeException(e);
            }


            /*
             * Respond with result
             */
            exchange.setStatusCode(201);
            exchange.getResponseHeaders().put(new HttpString("Content-Type"), "application/json; charset=utf-8");
            StringBuilder sb = new StringBuilder();
            sb.append("{");
            sb.append("\"sagaHandoffTimeMs\":").append(handoffTime);
            sb.append(",");
            sb.append("\"sagaExecutionTimeMs\":").append(executionTime);
            sb.append(",");
            sb.append("\"saga\":").append(SagaSerializer.toJson(polyglotSaga));
            sb.append(",");
            sb.append("\"log\":[");
            List<SagaLogEntry> sagaLogEntries = sagaLog.readEntries(executionId);
            for (int i = 0; i < sagaLogEntries.size(); i++) {
                SagaLogEntry sagaLogEntry = sagaLogEntries.get(i);
                if (i > 0) {
                    sb.append(",");
                }
                sb.append("{");
                sb.append("\"executionId\":").append(JSONObject.quote(sagaLogEntry.executionId)).append(",");
                sb.append("\"type\":").append(JSONObject.quote(sagaLogEntry.entryType.toString())).append(",");
                sb.append("\"nodeId\":").append(JSONObject.quote(sagaLogEntry.nodeId)).append(",");
                if (sagaLogEntry.sagaName != null) {
                    sb.append("\"sagaName\":").append(JSONObject.quote(sagaLogEntry.sagaName)).append(",");
                }
                if (sagaLogEntry.jsonData != null) {
                    sb.append("\"jsonData\":").append(sagaLogEntry.jsonData);
                }
                sb.append("}");
            }
            sb.append("]");
            sb.append("}");
            ByteBuffer responseData = ByteBuffer.wrap(sb.toString().getBytes(StandardCharsets.UTF_8));
            exchange.setResponseContentLength(responseData.limit());
            exchange.getResponseSender().send(responseData);
        };
    }
}
