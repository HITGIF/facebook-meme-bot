package com.example.chatbot;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.client.WebClientOptions;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.bytedeco.javacpp.Loader;
import org.bytedeco.javacpp.opencv_objdetect;
import org.bytedeco.javacpp.opencv_core;

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.Map;

import static org.bytedeco.javacpp.opencv_imgcodecs.imread;

public class FacebookBotVerticle extends AbstractVerticle {

    private static final String classifierURL = "https://raw.github.com/Itseez/opencv/2.4.0/data/haarcascades/haarcascade_frontalface_alt.xml";

    private String VERIFY_TOKEN;
    private String ACCESS_TOKEN;

    private opencv_objdetect.CascadeClassifier classifier;

    @Override
    public void start() throws Exception {

        updateProperties();

        Router router = Router.router(vertx);
        router.route().handler(BodyHandler.create());
        router.get("/webhook").handler(this::verify);
        router.post("/webhook").blockingHandler(this::message, false);

        vertx.createHttpServer().requestHandler(router::accept)
                .listen(
                        Integer.getInteger("http.port", 8080), System.getProperty("http.address", "0.0.0.0"));

        classifier = new opencv_objdetect.CascadeClassifier(
                opencv_core.cvLoad(Loader.extractResource(
                        new URL(classifierURL), null, "classifier", ".xml").getAbsolutePath()));
    }

    public static void main(String[] args) {
        Vertx vertx = Vertx.vertx();
        vertx.deployVerticle(new FacebookBotVerticle());
    }


    private void verify(RoutingContext routingContext) {
        String challenge = routingContext.request().getParam("hub.challenge");
        String token = routingContext.request().getParam("hub.verify_token");
        if (!VERIFY_TOKEN.equals(token)) {
            challenge = "fake";
        }

        routingContext.response()
                .putHeader("content-type", "application/json; charset=utf-8")
                .end(challenge);
    }

    private void message(RoutingContext routingContext) {

        routingContext.response()
                .putHeader("content-type", "application/json; charset=utf-8")
                .end("done");

        final JsonObject hook = routingContext.getBodyAsJson();

        JsonArray entries = hook.getJsonArray("entry");

        entries.getList().forEach((Object e) -> {

            System.out.println(e.getClass());

            Map entry = (Map) e;
            ArrayList messagingList = (ArrayList) entry.get("messaging");
            System.out.println(messagingList.getClass());
            messagingList.forEach((Object m) -> {

                Map messaging = (Map) m;
                Map sender = (Map) messaging.get("sender");
                messaging.put("recipient", sender);
                messaging.remove("sender");

                Map message = (Map) messaging.get("message");

                final ArrayList attachments = (ArrayList) message.get("attachments");
                if (attachments != null) {
                    try {
                        final URL imageUrl = new URL(((String) ((Map) ((Map) attachments.get(0)).get("payload")).get("url")));
                        final File imageFile = File.createTempFile("attachment-", FilenameUtils.getName(imageUrl.getPath()));
                        FileUtils.copyURLToFile(imageUrl, imageFile);

                        opencv_core.RectVector rectVector = new opencv_core.RectVector();
                        classifier.detectMultiScale(imread(imageFile.getAbsolutePath()), rectVector);

                        final long faceNumber = rectVector.size();

                        //Face Detected
                        if (rectVector.size() >= 0) {
                            message.put("text",
                                    "I see " +
                                    (faceNumber == 0 ? "no" : faceNumber) +
                                    " face" +
                                    (rectVector.size() > 1 ? "s" : "") +
                                    ".");
                        }

                    } catch (Exception e1) {
                        e1.printStackTrace();
                    }
                }

                message.remove("attachments");
                message.remove("mid");
                message.remove("seq");
                messaging.put("message", message);

                System.out.println(JsonObject.mapFrom(messaging));

                WebClientOptions options = new WebClientOptions();
                options.setSsl(true).setLogActivity(true);
                WebClient client = WebClient.create(vertx, options);


                client
                        .post(443, "graph.facebook.com", "/v2.6/me/messages/")
                        .addQueryParam("access_token", ACCESS_TOKEN)
                        .sendJsonObject(JsonObject.mapFrom(messaging), ar -> {
                            if (ar.succeeded()) {
                                // Obtain response
                                HttpResponse<Buffer> res = ar.result();

                                System.out.println("Received response with status code" + res.bodyAsString());
                            } else {
                                System.out.println("Something went wrong " + ar.cause().getMessage());
                            }
                        });
            });
        });
    }

    private void updateProperties() {

        VERIFY_TOKEN = System.getProperty("facebook.verify.token", "verify-token-default");
        ACCESS_TOKEN = System.getProperty("facebook.access.token", "access-token-default");

    }
}

