/*
 * Copyright (C) 2017 Glencoe Software, Inc. All rights reserved.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */

package com.glencoesoftware.omero.ms.image.region;

import java.util.Optional;

import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;

import com.esotericsoftware.minlog.Log;
import com.glencoesoftware.omero.ms.core.OmeroWebRedisSessionStore;
import com.glencoesoftware.omero.ms.core.OmeroWebSessionStore;
import com.glencoesoftware.omero.ms.core.RedisCacheVerticle;
import com.glencoesoftware.omero.ms.core.OmeroWebSessionRequestHandler;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.ReplyException;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.CookieHandler;
import omero.model.Image;

/**
 * Main entry point for the OMERO image region Vert.x microservice server.
 * @author Chris Allan <callan@glencoesoftware.com>
 * @author Emil Rozbicki <emil@glencoesoftware.com>
 *
 */
public class ImageRegionMicroserviceVerticle extends AbstractVerticle {

    private static final org.slf4j.Logger log =
            LoggerFactory.getLogger(ImageRegionMicroserviceVerticle.class);

    /** OMERO server Spring application context. */
    private ApplicationContext context;

    /** OMERO.web session store */
    private OmeroWebSessionStore sessionStore;

    /**
     * Entry point method which starts the server event loop and initializes
     * our current OMERO.web session store.
     */
    @Override
    public void start(Future<Void> future) {
        log.info("Starting verticle");

        if (config().getBoolean("debug")) {
            Logger root = (Logger) LoggerFactory.getLogger(
                    "com.glencoesoftware.omero.ms");
            root.setLevel(Level.DEBUG);
        }
        JsonObject memoizer = config().getJsonObject("memoizer");
        if (memoizer != null) {
            if (Optional.ofNullable(memoizer.getBoolean("debug"))
                    .orElse(Boolean.FALSE)) {
                log.info("Setting Kryo log level to DEBUG");
                Log.DEBUG();
            }
            if (Optional.ofNullable(memoizer.getBoolean("trace"))
                    .orElse(Boolean.FALSE)) {
                log.info("Setting Kryo log level to TRACE");
                Log.TRACE();
            }
        }

        // Set OMERO.server configuration options using system properties
        JsonObject omeroServer = config().getJsonObject("omero.server");
        omeroServer.forEach(entry -> {
            System.setProperty(entry.getKey(), (String) entry.getValue());
        });

        context = new ClassPathXmlApplicationContext(
                "classpath:ome/config.xml",
                "classpath:ome/services/datalayer.xml",
                "classpath*:beanRefContext.xml");

        // Deploy our dependency verticles
        JsonObject omero = config().getJsonObject("omero");
        DeploymentOptions options = new DeploymentOptions();
        options.setConfig(config());
        vertx.deployVerticle(new RedisCacheVerticle(), options);
        vertx.deployVerticle(new ImageRegionVerticle(
                omero.getString("host"), omero.getInteger("port"), context),
                new DeploymentOptions().setWorker(
                        true).setMultiThreaded(true));
        vertx.deployVerticle(new ShapeMaskVerticle(
                omero.getString("host"), omero.getInteger("port")),
                new DeploymentOptions().setWorker(
                        true).setMultiThreaded(true));

        HttpServer server = vertx.createHttpServer();
        Router router = Router.router(vertx);

        // Cookie handler so we can pick up the OMERO.web session
        router.route().handler(CookieHandler.create());

        // OMERO session handler which picks up the session key from the
        // OMERO.web session and joins it.
        JsonObject redis = config().getJsonObject("redis");
        sessionStore = new OmeroWebRedisSessionStore(redis.getString("uri"));
        router.route().handler(
                new OmeroWebSessionRequestHandler(sessionStore));

        // ImageRegion request handlers
        router.get(
                "/webgateway/render_image_region/:imageId/:theZ/:theT*")
            .handler(this::renderImageRegion);
        router.get(
                "/webgateway/render_image/:imageId/:theZ/:theT*")
            .handler(this::renderImageRegion);
        router.get(
                "/webclient/render_image_region/:imageId/:theZ/:theT*")
            .handler(this::renderImageRegion);
        router.get(
                "/webclient/render_image/:imageId/:theZ/:theT*")
            .handler(this::renderImageRegion);

        // ShapeMask request handlers
        router.get(
                "/webgateway/render_shape_mask/:shapeId*")
            .handler(this::renderShapeMask);

        int port = config().getInteger("port");
        log.info("Starting HTTP server *:{}", port);
        server.requestHandler(router::accept).listen(port, result -> {
            if (result.succeeded()) {
                future.complete();
            } else {
                future.fail(result.cause());
            }
        });
    }

    /**
     * Exit point method which when the verticle stops, cleans up our current
     * OMERO.web session store.
     */
    @Override
    public void stop() throws Exception {
        sessionStore.close();
    }

    /**
     * Render image region event handler.
     * Responds with an image body on success based on the <code>imageId</code>,
     * <code>z</code> and <code>t</code> encoded in the URL or HTTP 404 if the
     * {@link Image} does not exist or the user does not have permissions to
     * access it.
     * @param event Current routing context.
     */
    private void renderImageRegion(RoutingContext event) {
        log.info("Rendering image region");
        HttpServerRequest request = event.request();
        final ImageRegionCtx imageRegionCtx = new ImageRegionCtx(
                request.params(), event.get("omero.session_key"));

        final HttpServerResponse response = event.response();
        vertx.eventBus().<byte[]>send(
                ImageRegionVerticle.RENDER_IMAGE_REGION_EVENT,
                Json.encode(imageRegionCtx), result -> {
            try {
                if (result.failed()) {
                    Throwable t = result.cause();
                    int statusCode = 404;
                    if (t instanceof ReplyException) {
                        statusCode = ((ReplyException) t).failureCode();
                    }
                    response.setStatusCode(statusCode);
                    return;
                }
                byte[] imageRegion = result.result().body();
                String contentType = "application/octet-stream";
                if (imageRegionCtx.format.equals("jpeg")) {
                    contentType = "image/jpeg";
                }
                if (imageRegionCtx.format.equals("png")) {
                    contentType = "image/png";
                }
                if (imageRegionCtx.format.equals("tif")) {
                    contentType = "image/tiff";
                }
                response.headers().set("Content-Type", contentType);
                response.headers().set(
                        "Content-Length",
                        String.valueOf(imageRegion.length));
                response.write(Buffer.buffer(imageRegion));
            } finally {
                response.end();
                log.debug("Response ended");
            }
        });
    }

    /**
     * Render shape mask event handler.
     * Responds with a <code>image/png</code> body on success based
     * on the <code>shapeId</code> encoded in the URL or HTTP 404 if the
     * {@link Shape} does not exist or the user does not have permissions to
     * access it.
     * @param event Current routing context.
     */
    private void renderShapeMask(RoutingContext event) {
        log.info("Rendering shape mask");
        HttpServerRequest request = event.request();
        ShapeMaskCtx shapeMaskCtx = new ShapeMaskCtx(
                request.params(), event.get("omero.session_key"));

        final HttpServerResponse response = event.response();
        vertx.eventBus().<byte[]>send(
                ShapeMaskVerticle.RENDER_SHAPE_MASK_EVENT,
                Json.encode(shapeMaskCtx), result -> {
            try {
                if (result.failed()) {
                    Throwable t = result.cause();
                    int statusCode = 404;
                    if (t instanceof ReplyException) {
                        statusCode = ((ReplyException) t).failureCode();
                    }
                    response.setStatusCode(statusCode);
                    return;
                }
                byte[] shapeMask = result.result().body();
                response.headers().set("Content-Type", "image/png");
                response.headers().set(
                        "Content-Length",
                        String.valueOf(shapeMask.length));
                response.write(Buffer.buffer(shapeMask));
            } finally {
                response.end();
                log.debug("Response ended");
            }
        });
    }

}
