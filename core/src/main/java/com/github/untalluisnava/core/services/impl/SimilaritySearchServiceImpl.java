package com.github.untalluisnava.core.services.impl;

import static com.github.untalluisnava.core.util.Constants.VENDOR;

import com.day.cq.wcm.api.Page;
import com.day.cq.wcm.api.PageManager;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.untalluisnava.core.services.SimilaritySearchService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import javax.jcr.query.Query;
import org.apache.commons.lang3.stream.Streams;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.propertytypes.ServiceDescription;
import org.osgi.service.component.propertytypes.ServiceVendor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author lnava
 */
@Component
@ServiceVendor(VENDOR)
@ServiceDescription("Provides a list of similar pages according to their content")
public class SimilaritySearchServiceImpl implements SimilaritySearchService {

  private static final String OLLAMA_URL = "http://localhost:11434/api/embeddings";
  private static final String MODEL = "mxbai-embed-large";
  private static final int MAX_CHARS_PER_CHUNK = 1024;

  private final Logger log = LoggerFactory.getLogger(getClass());

  private HttpClient httpClient;
  private ObjectMapper mapper;

  @Activate
  void init() {
    this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    this.mapper = new ObjectMapper();
  }

  @Override
  public List<Page> getMostSimilarPages(Page reference) {
    log.info("Calculating pages similar to {}", reference.getPath());

    Page parent = reference.getParent();
    ResourceResolver resourceResolver = reference.getContentResource().getResourceResolver();

    String query =
        String.format(
            "SELECT * FROM [nt:unstructured] AS s WHERE s.[sling:resourceType] = 'aem-pocs/components/text' AND ISDESCENDANTNODE(s, '%s')",
            parent.getPath());

    Iterator<Resource> iterator = resourceResolver.findResources(query, Query.JCR_SQL2);

    PageManager pageManager = reference.getPageManager();
    // Calculating embeddings
    List<Holder> lstHolders =
        Streams.of(iterator).map(resource -> process(pageManager, resource)).toList();

    Map<String, Holder> mapOfHolders =
        lstHolders.stream()
            .collect(Collectors.toMap(holder -> holder.getPage().getPath(), holder -> holder));

    Holder referenceHolder = mapOfHolders.get(reference.getPath());

    calculateSimilarity(referenceHolder, lstHolders);

    return lstHolders.stream()
        .filter(h -> !h.equals(referenceHolder))
        .sorted(new SimilarityComparator().reversed())
        .map(
            holder -> { // Trick to get the similarity's value.
              log.info(
                  "Similarity of {} vs {}: {}",
                  reference.getName(),
                  holder.getPage().getName(),
                  holder.getSimilarity());
              return holder;
            })
        .map(Holder::getPage)
        .toList();
  }

  private Holder process(PageManager pageManager, Resource resource) {

    Page page = pageManager.getContainingPage(resource);

    Holder holder = new Holder(page);

    String text = extractCleanText(resource);

    try {
      holder.setEmbeddings(embedText(text));
    } catch (Exception e) {
      holder.setEmbeddings(new double[MAX_CHARS_PER_CHUNK]);
    }

    return holder;
  }

  private String extractCleanText(Resource resource) {
    ValueMap vm = resource.getValueMap();

    String htmlText = vm.get("text", "");

    Document document = Jsoup.parse(htmlText);

    return document.text();
  }

  private void calculateSimilarity(Holder holder, List<Holder> lstHolders) {
    for (Holder h : lstHolders) {
      double similarity;
      if (holder.equals(h)) {
        similarity = 1.0;
      } else {
        similarity = cosineSimilarity(holder.getEmbeddings(), h.getEmbeddings());
      }

      h.setSimilarity(similarity);
    }
  }

  public double[] embedChunk(String text) throws Exception {

    EmbeddingRequest embeddingRequest = new EmbeddingRequest(MODEL, text);

    String payload = mapper.writeValueAsString(embeddingRequest);

    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(OLLAMA_URL))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(60))
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build();

    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

    if (response.statusCode() != 200) {
      throw new RuntimeException(
          "Ollama error (" + response.statusCode() + "): " + response.body());
    }

    EmbeddingResponse embeddingResponse =
        mapper.readValue(response.body(), EmbeddingResponse.class);

    return embeddingResponse.embedding();
  }

  private List<String> splitParagraph(String paragraph) {
    List<String> pieces = new ArrayList<>();

    if (paragraph.length() <= MAX_CHARS_PER_CHUNK) {
      pieces.add(paragraph);
      return pieces;
    }

    String[] sentences = paragraph.split("(?<=[.!?])\\s+");
    StringBuilder current = new StringBuilder();

    for (String sentence : sentences) {
      // Oración individual más grande que el límite: corte duro por caracteres.
      if (sentence.length() > MAX_CHARS_PER_CHUNK) {
        if (current.length() > 0) {
          pieces.add(current.toString().trim());
          current = new StringBuilder();
        }
        for (int i = 0; i < sentence.length(); i += MAX_CHARS_PER_CHUNK) {
          pieces.add(sentence.substring(i, Math.min(i + MAX_CHARS_PER_CHUNK, sentence.length())));
        }
        continue;
      }

      if (current.length() + sentence.length() > MAX_CHARS_PER_CHUNK && current.length() > 0) {
        pieces.add(current.toString().trim());
        current = new StringBuilder();
      }
      current.append(sentence).append(' ');
    }

    if (current.length() > 0) {
      pieces.add(current.toString().trim());
    }

    return pieces;
  }

  public double[] embedText(String articleText) throws Exception {
    List<String> chunks = chunkText(articleText);

    if (chunks.size() == 1) {
      return embedChunk(chunks.get(0));
    }

    double[] sum = null;

    for (String chunk : chunks) {

      double[] v = embedChunk(chunk);

      if (sum == null) {
        sum = new double[v.length];
      }

      for (int i = 0; i < v.length; i++) {
        sum[i] += v[i];
      }
    }

    for (int i = 0; i < sum.length; i++) {
      sum[i] /= chunks.size();
    }

    return sum;
  }

  public List<String> chunkText(String text) {
    List<String> chunks = new ArrayList<>();
    String[] paragraphs = text.split("\\n\\s*\\n"); // separa por párrafos

    StringBuilder current = new StringBuilder();
    for (String p : paragraphs) {
      p = p.trim();
      if (p.isEmpty()) {
        continue;
      }

      // Un solo párrafo puede exceder el límite del modelo: hay que subdividirlo
      // antes de agregarlo, de lo contrario Ollama recibiría más tokens de los
      // que admite y lanzaría una excepción.
      for (String piece : splitParagraph(p)) {
        if (current.length() + piece.length() > MAX_CHARS_PER_CHUNK && current.length() > 0) {
          chunks.add(current.toString().trim());
          current = new StringBuilder();
        }
        current.append(piece).append("\n\n");
      }
    }
    if (current.length() > 0) {
      chunks.add(current.toString().trim());
    }

    return chunks;
  }

  /**
   * Calculates the cosine similarity between two vectors.
   *
   * @param v1
   * @param v2
   * @return
   */
  public static double cosineSimilarity(double[] v1, double[] v2) {
    if (v1.length != v2.length) {
      throw new IllegalArgumentException("The vectors must have the same dimension.");
    }

    double dot = 0, normA = 0, normB = 0;

    for (int i = 0; i < v1.length; i++) {
      dot += v1[i] * v2[i];
      normA += v1[i] * v1[i];
      normB += v2[i] * v2[i];
    }

    if (normA == 0 || normB == 0) {
      return 0.0;
    }

    return dot / (Math.sqrt(normA) * Math.sqrt(normB));
  }

  public class SimilarityComparator implements Comparator<Holder> {

    @Override
    public int compare(Holder h1, Holder h2) {

      return Double.compare(h1.getSimilarity(), h2.getSimilarity());
    }
  }

  public class Holder {

    private final Page page;
    private double[] embeddings;
    private double similarity;

    public Holder(Page page) {
      this.page = page;
    }

    public double[] getEmbeddings() {
      return embeddings;
    }

    public void setEmbeddings(double[] embeddings) {
      this.embeddings = embeddings;
    }

    public Page getPage() {
      return page;
    }

    public double getSimilarity() {
      return similarity;
    }

    public void setSimilarity(double similarity) {
      this.similarity = similarity;
    }

    @Override
    public int hashCode() {
      int hash = 3;
      hash = 43 * hash + Objects.hashCode(this.page);
      return hash;
    }

    @Override
    public boolean equals(Object obj) {
      if (this == obj) {
        return true;
      }
      if (obj == null) {
        return false;
      }
      if (getClass() != obj.getClass()) {
        return false;
      }
      final Holder other = (Holder) obj;
      return Objects.equals(this.page, other.page);
    }
  }

  public record EmbeddingRequest(String model, String prompt) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record EmbeddingResponse(double[] embedding) {}
}
