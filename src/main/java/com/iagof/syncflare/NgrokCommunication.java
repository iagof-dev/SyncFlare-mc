package com.iagof.syncflare;

import com.github.alexdlaird.ngrok.NgrokClient;
import com.github.alexdlaird.ngrok.conf.JavaNgrokConfig;
import com.github.alexdlaird.ngrok.installer.NgrokInstaller;
import com.github.alexdlaird.ngrok.installer.NgrokVersion;
import com.github.alexdlaird.ngrok.protocol.CreateTunnel;
import com.github.alexdlaird.ngrok.protocol.Proto;
import com.github.alexdlaird.ngrok.protocol.Region;
import com.github.alexdlaird.ngrok.protocol.Tunnel;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.GenericEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.EventListener;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import net.dv8tion.jda.api.utils.messages.MessageEditData;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.json.simple.JSONObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class NgrokCommunication extends JavaPlugin implements EventListener {

    private JDA client;
    private NgrokClient ngrokClient;
    private String publicIp;

    private boolean isCloudflared = false;
    private String domainCF;

    private boolean discordModule;
    private boolean discordModuleStatus = false;

    @Override
    public void onEnable() {

        Logger.getLogger(String.valueOf(com.github.alexdlaird.ngrok.process.NgrokProcess.class)).setLevel(Level.OFF);

        this.saveDefaultConfig();

        if(!this.getConfig().getBoolean("ENABLED")){
            Bukkit.getPluginManager().disablePlugin(this);
            return; //IDK if its necessary... anyway
        }

        int ngrokPort = this.getServer().getPort();
        this.discordModule = this.getConfig().getBoolean("DISCORD_UPDATES.ENABLED");

        if (discordModule) {
            String botToken = this.getConfig().getString("DISCORD_UPDATES.BOT_TOKEN");
            if (botToken == null || botToken.isEmpty()) {
                this.getLogger().warning("Bot token is missing in the config. Shutting down...");
                this.setEnabled(false);
                return;
            }

            this.client = JDABuilder.createDefault(botToken)
                    .setStatus(OnlineStatus.DO_NOT_DISTURB)
                    .enableIntents(Arrays.asList(GatewayIntent.DIRECT_MESSAGES, GatewayIntent.MESSAGE_CONTENT, GatewayIntent.GUILD_MESSAGES))
                    .build();

            this.client.addEventListener(this);

            try {
                this.client.awaitReady();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        final JavaNgrokConfig javaNgrokConfig = new JavaNgrokConfig.Builder()
                .withNgrokVersion(NgrokVersion.V3)
                .withRegion(Region.valueOf(Objects.requireNonNull(this.getConfig().getString("NGROK_SETTINGS.REGION")).toUpperCase()))
                .build();

        this.ngrokClient = new NgrokClient.Builder()
                .withNgrokInstaller(new NgrokInstaller())
                .withJavaNgrokConfig(javaNgrokConfig)
                .build();

        this.ngrokClient.getNgrokProcess().setAuthToken(this.getConfig().getString("NGROK_SETTINGS.AUTH_TOKEN"));

        final CreateTunnel createTunnel = new CreateTunnel.Builder()
                .withProto(Proto.TCP)
                .withAddr(ngrokPort) // Use the configured Ngrok port
                .build();

        final Tunnel tunnel = ngrokClient.connect(createTunnel);
        this.publicIp = tunnel.getPublicUrl().replace("tcp://", "");

        if(this.getConfig().getBoolean("CLOUDFLARE_SETTINGS.ENABLED")){

            String[] parts = this.publicIp.split(":");
            String address = parts[0];
            int port = Integer.parseInt(parts[1]);

            JSONObject mainData = new JSONObject();
            mainData.put("type", "SRV");
            mainData.put("name", "_minecraft._tcp." + this.getConfig().getString("CLOUDFLARE_SETTINGS.DNS_NAME"));
            JSONObject data = new JSONObject();
            data.put("port", port);
            data.put("priority", 0);
            data.put("weight", 0);
            data.put("target", address);
            mainData.put("data", data);
            mainData.put("comment", "update port by SF");

            String url = "https://api.cloudflare.com/client/v4/zones/" + this.getConfig().getString("CLOUDFLARE_SETTINGS.ZONE_ID") + "/dns_records/" + this.getConfig().getString("CLOUDFLARE_SETTINGS.DNS_RECORD_ID");

            try {
                makePatchRequest(url, this.getConfig().getString("CLOUDFLARE_SETTINGS.EMAIL"), this.getConfig().getString("CLOUDFLARE_SETTINGS.API_KEY"), mainData);
                this.isCloudflared = true;
                this.domainCF = this.getConfig().getString("CLOUDFLARE_SETTINGS.DNS_NAME");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        if (discordModuleStatus) {
            String updateMessage = this.getConfig().getString("DISCORD_UPDATES.UPDATE_MESSAGE");
            if (updateMessage != null && !updateMessage.isEmpty()) {
                TextChannel messageChannel = client.getTextChannelById(Objects.requireNonNull(this.getConfig().getString("DISCORD_UPDATES.UPDATE_CHANNEL_ID")));
                if (messageChannel != null) {
                    long updateMessageId = this.getConfig().getLong("DISCORD_UPDATES.UPDATE_MESSAGE_ID");
                    if (updateMessageId == 0) {
                        CompletableFuture<Message> message = messageChannel.sendMessage(MessageCreateData.fromContent(updateMessage.replace("%server_ip%", publicIp))).submit();
                        try {
                            this.getConfig().set("DISCORD_UPDATES.UPDATE_MESSAGE_ID", message.get().getIdLong());
                        } catch (InterruptedException | ExecutionException e) {
                            throw new RuntimeException(e);
                        }
                    } else {
                        messageChannel.editMessageById(updateMessageId, MessageEditData.fromContent(updateMessage.replace("%server_ip%", publicIp))).queue();
                    }
                } else {
                    this.getLogger().warning("IP update channel is null. Update message not sent.");
                }
            } else {
                this.getLogger().warning("IP update message is missing in the config. Update message not sent.");
            }
        }

        if(isCloudflared) {
            this.getLogger().info("Listening server on domain '" + this.domainCF + "'");
            return;
        }
        this.getLogger().info("Listening server on NGROK '" + this.publicIp + ":" + ngrokPort + "'");
    }

    @Override
    public void onDisable() {
        try {
            if (ngrokClient != null && publicIp != null) {
                this.ngrokClient.disconnect(publicIp);
                this.ngrokClient.kill();
            }
            if (discordModuleStatus) {
                this.client.shutdown();
            }
            this.saveConfig();
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onEvent(@NotNull GenericEvent genericEvent) {
        if (genericEvent instanceof ReadyEvent) {
            this.discordModuleStatus = true;
        }
    }

    private void makePatchRequest(String url, String cfEmail, String cfApiKey, JSONObject data) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(new URI(url))
                .header("Content-Type", "application/json")
                .header("X-Auth-Email", cfEmail)
                .header("X-Auth-Key", cfApiKey)
                .method("PATCH", HttpRequest.BodyPublishers.ofString(data.toString()))
                .build();

        client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
