package com.netgrif.maven.plugin.module;

import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.entity.mime.HttpMultipartMode;
import org.apache.hc.client5.http.entity.mime.MultipartEntityBuilder;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.HttpEntityWrapper;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.*;
import org.apache.maven.project.MavenProject;
import org.apache.maven.settings.Settings;

import java.io.File;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;

@Mojo(name = "deploy", defaultPhase = LifecyclePhase.DEPLOY,
        requiresDependencyCollection = ResolutionScope.COMPILE_PLUS_RUNTIME,
        requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME)
public class DeployModuleMojo extends AbstractMojo {

    private final Log log = getLog();

    @Component
    private MavenProject project;

    @Component
    private MavenSession session;

    @Parameter(defaultValue = "${settings}")
    private Settings settings;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {

    }

    public void uploadModuleFile(File moduleFile, String repositoryUrl) {
        final HttpPost httpPost = new HttpPost(repositoryUrl);

        final BasicCredentialsProvider credentialsProvider = new BasicCredentialsProvider();
        final UsernamePasswordCredentials credentials = new UsernamePasswordCredentials("username", "password".toCharArray());
        credentialsProvider.setCredentials(new AuthScope(repositoryUrl, 443), credentials);

        final MultipartEntityBuilder builder = MultipartEntityBuilder.create();
        builder.setMode(HttpMultipartMode.LEGACY);
        builder.addBinaryBody("file", moduleFile, ContentType.APPLICATION_OCTET_STREAM, moduleFile.getName());
        final HttpEntity entity = builder.build();

        final ProgressListener progressListener = percentage -> log.info("File upload progress: "+percentage);
        httpPost.setEntity(new ProgressEntityWrapper(entity, progressListener));

        try (CloseableHttpClient client = HttpClients.custom()
                .setDefaultCredentialsProvider(credentialsProvider)
                .build()) {
            client.execute(httpPost, response -> {
                log.info(response.getCode() + " " + response.getReasonPhrase());
                return "";
            });
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static interface ProgressListener {
        void progress(float percentage);
    }

    public static class ProgressEntityWrapper extends HttpEntityWrapper {

        private final ProgressListener progressListener;

        public ProgressEntityWrapper(HttpEntity wrappedEntity, ProgressListener progressListener) {
            super(wrappedEntity);
            this.progressListener = progressListener;
        }

        @Override
        public void writeTo(OutputStream outStream) throws IOException {
            super.writeTo(new CountingOutputStream(outStream, progressListener, getContentLength()));
        }
    }

    public static class CountingOutputStream extends FilterOutputStream {

        private final ProgressListener progressListener;
        private final long total;
        private long transferred;

        public CountingOutputStream(OutputStream out, ProgressListener progressListener, long totalBytes) {
            super(out);
            this.progressListener = progressListener;
            this.total = totalBytes;
            transferred = 0;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            transferred += len;
            progressListener.progress(getCurrentProgress());
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            transferred++;
            progressListener.progress(getCurrentProgress());
        }

        private float getCurrentProgress() {
            return ((float) transferred / total) * 100;
        }
    }
}
