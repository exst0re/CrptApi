import java.net.http.HttpRequest;
import java.util.concurrent.TimeUnit;

public class CrptApi {
    private final TimeUnit timeUnit;
    private final int requestLimit;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    private final Semaphore semaphore;
    private final ReentrantLock lock;

    private final Deque<Instant> requestTimestamps;

    public CrptApi(TimeUnit timeUnit, int requestLimit) {
        if (requestLimit <= 0) {
            throw new IllegalArgumentException("Request limit must be positive");
        }

        this.timeUnit = timeUnit;
        this.requestLimit = requestLimit;

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();

        this.objectMapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);

        this.semaphore = new Semaphore(requestLimit);
        this.lock = new ReentrantLock();
        this.requestTimestamps = new ArrayDeque<>(requestLimit);
    }

    /**
     * Создание документа для ввода в оборот товара, произведенного в РФ.
     * Метод thread-safe и соблюдает ограничения на частоту запросов.
     *
     * @param document  объект документа для создания
     * @param signature подпись документа
     * @throws RuntimeException если произошла ошибка при выполнении запроса
     */
    public void createDocument(Document document, String signature) {
        try {
            acquirePermission();

            DocumentRequest requestWrapper = new DocumentRequest(document, signature);

            String requestBody = objectMapper.writeValueAsString(requestWrapper);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://ismp.crpt.ru/api/v3/lk/documents/create"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofSeconds(30))
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 400) {
                throw new ApiException("API request failed with status: " + response.statusCode());
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Request was interrupted", e);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize document", e);
        } catch (IOException e) {
            throw new RuntimeException("HTTP request failed", e);
        }
    }

    /**
     * Метод для получения разрешения на выполнение запроса.
     * Реализует алгоритм "скользящего окна" для контроля частоты запросов.
     *
     * @throws InterruptedException если поток был прерван во время ожидания
     */
    private void acquirePermission() throws InterruptedException {
        lock.lock();
        try {
            Instant now = Instant.now();
            Instant timeWindowStart = now.minusMillis(timeUnit.toMillis(1));

            while (!requestTimestamps.isEmpty() &&
                    requestTimestamps.peek().isBefore(timeWindowStart)) {
                requestTimestamps.poll();
                semaphore.release();
            }

            if (semaphore.tryAcquire()) {
                requestTimestamps.offer(now);
                return;
            }
        } finally {
            lock.unlock();
        }

        semaphore.acquire();

        lock.lock();
        try {
            requestTimestamps.offer(Instant.now());
        } finally {
            lock.unlock();
        }
    }
}

@Getter
@Setter
public static class Document {
    private Description description;
    private String docId;
    private String docStatus;
    private String docType;
    private boolean importRequest;
    private String ownerInn;
    private String participantInn;
    private String producerInn;
    private String productionDate;
    private String productionType;
    private Product[] products;
    private String certificateDoc;
    private String certificateDocDate;
    private String certificateDocNumber;
    private String regDate;
    private String regNumber;
}

@Getter
@Setter
public static class Description {
    private String participantInn;

    public String getParticipantInn() { return participantInn; }
    public void setParticipantInn(String participantInn) { this.participantInn = participantInn; }
}

@Getter
@Setter
public static class Product {
    private String certificateDocument;
    private String certificateDocumentDate;
    private String certificateDocumentNumber;
    private String ownerInn;
    private String producerInn;
    private String productionDate;
    private String tnvedCode;
    private String uitCode;
    private String uituCode;
}

private static class DocumentRequest {
    private final Document document;
    private final String signature;

    public DocumentRequest(Document document, String signature) {
        this.document = document;
        this.signature = signature;
    }

    public Document getDocument() { return document; }
    public String getSignature() { return signature; }
}

public static class ApiException extends RuntimeException {
    public ApiException(String message) {
        super(message);
    }

    public ApiException(String message, Throwable cause) {
        super(message, cause);
    }
}