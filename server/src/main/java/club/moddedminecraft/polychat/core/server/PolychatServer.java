package club.moddedminecraft.polychat.core.server;

import club.moddedminecraft.polychat.core.messagelibrary.ChatProtos;
import club.moddedminecraft.polychat.core.messagelibrary.PolychatProtobufMessageDispatcher;
import club.moddedminecraft.polychat.core.server.discordcommands.ExecCommand;
import club.moddedminecraft.polychat.core.server.discordcommands.OnlineCommand;
import club.moddedminecraft.polychat.core.server.discordcommands.TpsCommand;
import club.moddedminecraft.polychat.core.server.handlers.*;
import com.google.protobuf.Any;

import com.jagrosh.jdautilities.command.CommandClient;
import com.jagrosh.jdautilities.command.CommandClientBuilder;
import com.jagrosh.jdautilities.commons.waiter.EventWaiter;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.TextChannel;
import net.dv8tion.jda.api.events.GenericEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;

import club.moddedminecraft.polychat.core.networklibrary.Server;
import club.moddedminecraft.polychat.core.networklibrary.Message;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.security.auth.login.LoginException;

import java.io.IOException;
import java.util.HashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

public final class PolychatServer {
    private final ConcurrentLinkedDeque<GenericEvent> queue;
    private final Server server;
    private final PolychatProtobufMessageDispatcher polychatProtobufMessageDispatcher;
    private final JDA jda;
    private final HashMap<String, OnlineServer> onlineServers;
    private final TextChannel generalChannel;

    private final static Logger logger = LoggerFactory.getLogger(PolychatServer.class);
    public static final int TICK_TIME_IN_MILLIS = 50;

    private PolychatServer() throws IOException, LoginException, InterruptedException {
        // set up JDA event queue & servers hashmap;
        queue = new ConcurrentLinkedDeque<GenericEvent>();
        onlineServers = new HashMap<>();

        // set up JDA commands;
        CommandClient commandClient = new CommandClientBuilder()
                .setOwnerId("") // will need to be retrieved from YAML;
                .setPrefix("!")
                .addCommands(
                        new OnlineCommand(onlineServers),
                        new TpsCommand(onlineServers),
                        new ExecCommand(onlineServers)
                )
                .build();

        // set up main JDA;
        jda = JDABuilder.createDefault("") // will need to be retrieved from YAML;
                .addEventListeners(
                        commandClient,
                        new GenericJdaEventHandler(queue)
                )
                .build()
                .awaitReady();
        generalChannel = jda.getTextChannelById(""); // same as above here;

        // set up TCP;
        server = new Server(5005, 128);

        // set up Protobuf message handlers;
        polychatProtobufMessageDispatcher = new PolychatProtobufMessageDispatcher();
        polychatProtobufMessageDispatcher.addEventHandlers(
                new ChatMessageHandler(generalChannel, onlineServers),
                new PromoteMemberCommandHandler(generalChannel, onlineServers),
                new ServerInfoMessageHandler(onlineServers),
                new ServerStatusMessageHandler(onlineServers, generalChannel),
                new PlayersOnlineMessageHandler(onlineServers),
                new PlayerStatusChangedMessageHandler(onlineServers, generalChannel),
                new GenericCommandResultMessageHandler(jda)
        );
    }

    public static void main(String[] args) {
        try {
            new PolychatServer().spin();
        } catch (IOException | LoginException | InterruptedException e) {
            logger.error("Error while starting Polychat server", e);
        }
    }

    private void spin() {
        while (true) {
            long start = System.currentTimeMillis();
            spinOnce();
            long elapsed = System.currentTimeMillis() - start;
            long sleepFor = TICK_TIME_IN_MILLIS - elapsed;
            if (sleepFor > 0) {
                try {
                    Thread.sleep(sleepFor);
                } catch (InterruptedException ignored) {
                }
            }
        }
    }

    private void spinOnce() {
        try {
            for (Message message : server.poll()) {
                polychatProtobufMessageDispatcher.handlePolychatMessage(message);
            }

            GenericEvent nextEvent;
            while ((nextEvent = queue.poll()) != null) {
                if (nextEvent instanceof MessageReceivedEvent) {
                    MessageReceivedEvent ev = (MessageReceivedEvent)nextEvent;
                    net.dv8tion.jda.api.entities.Message discordMsg = ev.getMessage();

                    // ignore messages from self and other bots;
                    if (ev.getAuthor().isBot()) {
                        break;
                    }

                    // ignore commands in this event handler;
                    if (discordMsg.getContentRaw().startsWith("!")) {
                        break;
                    }

                    // ignore non-#general
                    if (discordMsg.getChannel().getId().equals(generalChannel.getId())) {
                        break;
                    }

                    // construct Protobuf chat message from Discord message;
                    String msgStringForClients =
                            "[Discord] " + discordMsg.getAuthor().getName() + ": " + discordMsg.getContentRaw();
                    ChatProtos.ChatMessage protoChatMessage = ChatProtos.ChatMessage.newBuilder()
                            .setServerId("Discord")
                            .setMessage(msgStringForClients)
                            .setMessageOffset(msgStringForClients.indexOf(':'))
                            .build();

                    // pack the message;
                    Any packedMsg = Any.pack(protoChatMessage);

                    // convert the message to bytes;
                    byte[] msgBytes = packedMsg.toByteArray();

                    // send the message to MC clients;
                    server.broadcastMessageToAll(msgBytes);
                }
            }
        } catch (IOException e) {
            logger.error("Error occurred in Polychat server event loop", e);
        }
    }
}
