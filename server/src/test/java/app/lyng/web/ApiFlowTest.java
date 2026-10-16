package app.lyng.web;

import static org.assertj.core.api.Assertions.assertThat;

import app.lyng.support.IntegrationTest;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 通过真实的 HTTP 走一遍：首页、一场戏（流）、私聊（流）、人物主页、幕后、设置，以及错误的说法。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiFlowTest extends IntegrationTest {

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    record Event(String name, JsonNode data) {}

    private HttpResponse<String> call(String method, String path, String body) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api" + path))
                .header("content-type", "application/json");
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode get(String path) throws IOException, InterruptedException {
        HttpResponse<String> r = call("GET", path, null);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }

    private JsonNode post(String path, String body) throws IOException, InterruptedException {
        HttpResponse<String> r = call("POST", path, body);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return r.body().isEmpty() ? null : json.readTree(r.body());
    }

    private List<Event> stream(String path, String body) throws IOException, InterruptedException {
        HttpResponse<String> r = call("POST", path, body == null ? "{}" : body);
        assertThat(r.headers().firstValue("content-type").orElse("")).startsWith("text/event-stream");
        List<Event> events = new ArrayList<>();
        String name = null;
        for (String line : r.body().split("\n")) {
            if (line.startsWith("event:")) {
                name = line.substring(6).strip();
            } else if (line.startsWith("data:")) {
                events.add(new Event(name, json.readTree(line.substring(5))));
            }
        }
        return events;
    }

    private static List<String> names(List<Event> events) {
        return events.stream().map(Event::name).toList();
    }

    @Test
    void aWholeVisit() throws Exception {
        JsonNode home = get("/home");
        assertThat(home.path("worlds").get(0).path("name").asString()).isEqualTo("诛仙");
        assertThat(home.path("worlds").get(0).path("characters")).hasSize(3);

        String scene = post("/scenes", """
                {"worldId":"%s","cast":["%s","%s"],"possessedId":"%s","tone":"daily","setup":"山海苑，入夜。"}"""
                .formatted(world, xf, lu, xf)).path("id").asString();
        assertThat(names(stream("/scenes/" + scene + "/resume", null))).containsExactly("status", "saved", "done", "end");
        List<Event> act = stream("/scenes/" + scene + "/act", "{\"action\":\"say\",\"text\":\"雪琪。\"}");
        assertThat(names(act)).startsWith("saved", "status").endsWith("done", "end");
        assertThat(act.getFirst().data().path("lines").get(0).path("text").asString()).isEqualTo("雪琪。");

        JsonNode page = get("/scenes/" + scene);
        assertThat(page.path("lines").size()).isGreaterThanOrEqualTo(3);
        assertThat(page.path("scene").path("cast")).hasSize(2);

        List<Event> busy = stream("/scenes/" + scene + "/act", "{\"action\":\"continue\"}");
        assertThat(busy.getFirst().name()).isEqualTo("error");
        assertThat(busy.getFirst().data().path("status").asInt()).isEqualTo(400);

        assertThat(names(stream("/scenes/" + scene + "/end", "{\"closing\":true}"))).contains("closed");
        settle();

        JsonNode person = get("/characters/" + lu);
        assertThat(person.path("name").asString()).isEqualTo("陆雪琪");
        assertThat(person.path("openScene").isNull()).isTrue();
        assertThat(get("/characters/" + lu + "/experiences").get(0).path("sourceLabel").asString()).isEqualTo("第1场");
        get("/characters/" + lu + "/memories");

        String chat = post("/chats", "{\"playedId\":\"%s\",\"otherId\":\"%s\"}".formatted(xf, lu)).path("id").asString();
        List<Event> sent = stream("/chats/" + chat + "/send", "{\"text\":\"睡了吗？\"}");
        assertThat(names(sent)).startsWith("saved").endsWith("done", "end");
        assertThat(get("/chats/" + chat).path("messages").size()).isGreaterThanOrEqualTo(2);
        assertThat(get("/chats")).hasSize(1);

        JsonNode runs = get("/runs?sceneId=" + scene);
        assertThat(runs.size()).isGreaterThanOrEqualTo(3);
        JsonNode run = get("/runs/" + runs.get(0).path("id").asString());
        assertThat(run.path("request").path("messages").isArray()).isTrue();
        assertThat(run.path("calls")).isNotEmpty();
        assertThat(get("/usage").path("purposes")).isNotEmpty();

        HttpResponse<String> settings = call("PUT", "/settings", "{\"scale\":\"adult\",\"budgetUsd\":2}");
        assertThat(json.readTree(settings.body()).path("scale").asString()).isEqualTo("adult");
    }

    @Test
    void errorsSpeakPlainly() throws Exception {
        HttpResponse<String> missing = call("GET", "/scenes/" + java.util.UUID.randomUUID(), null);
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(json.readTree(missing.body()).path("error").asString()).isEqualTo("没有这一场戏");

        HttpResponse<String> invalid = call("POST", "/scenes", "{\"worldId\":\"%s\",\"cast\":[\"%s\"],\"possessedId\":\"%s\",\"tone\":\"daily\",\"setup\":\"x\"}"
                .formatted(world, lu, lu));
        assertThat(invalid.statusCode()).isEqualTo(400);
        assertThat(json.readTree(invalid.body()).path("error").asString()).contains("不由你附身");

        HttpResponse<String> garbled = call("POST", "/scenes", "{not json");
        assertThat(garbled.statusCode()).isEqualTo(400);

        HttpResponse<String> settings = call("PUT", "/settings", "{\"scale\":\"wild\"}");
        assertThat(settings.statusCode()).isEqualTo(400);
    }
}
