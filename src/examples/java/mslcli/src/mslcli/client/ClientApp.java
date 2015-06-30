/**
 * Copyright (c) 2014 Netflix, Inc.  All rights reserved.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package mslcli.client;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.io.IOException;
import java.net.URL;
import java.security.Security;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import com.netflix.msl.MslException;
import com.netflix.msl.crypto.ICryptoContext;
import com.netflix.msl.keyx.AsymmetricWrappedExchange;
import com.netflix.msl.msg.ConsoleFilterStreamFactory;
import com.netflix.msl.msg.MslControl;
import com.netflix.msl.tokens.MasterToken;
import com.netflix.msl.tokens.ServiceToken;
import com.netflix.msl.tokens.UserIdToken;
import com.netflix.msl.userauth.EmailPasswordAuthenticationData;
import com.netflix.msl.userauth.UserAuthenticationData;

import mslcli.common.Pair;
import mslcli.client.msg.MessageConfig;
import mslcli.client.util.UserAuthenticationDataHandle;
import mslcli.common.util.AppContext;
import mslcli.common.util.MslProperties;
import mslcli.common.util.MslStoreWrapper;
import mslcli.common.util.SharedUtil;

import static mslcli.client.CliCmdParameters.*;

/**
 * MSL client launcher program. Allows to configure message security policies and key exchange mechanism.
 *
 * @author Vadim Spector <vspector@netflix.com>
 */

public final class ClientApp {
    // Add BouncyCastle provider.
    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    // commands
    private static final String CMD_MSG  = "msg"; // send message
    private static final String CMD_CFG  = "cfg" ; // configure message properties
    private static final String CMD_KX   = "kx"  ; // select key exchange
    private static final String CMD_HELP = "help"; // help

    private static final List<String> supportedCommands =
        Collections.unmodifiableList(new ArrayList<String>(Arrays.asList(CMD_MSG, CMD_CFG, CMD_KX, CMD_HELP)));

    private static final String MSG_ENCRYPTION_PROMPT = "Encrypted";
    private static final String MSG_INTEGRITY_PROMPT  = "Integrity Protected";
    private static final String MSG_NONREPLAY_PROMPT  = "Non-Replayable";
    private static final String USER_ID_PROMPT        = "User ID";

    private static final String YES  = "y";
    private static final String NO   = "n";
    private static final String QUIT = "q";

    public static void main(String[] args) throws Exception {
        final CliCmdParameters cmdParam = new CliCmdParameters(args);
        System.out.println("Parameters: " + cmdParam.getParameters());
        final ClientApp clientApp = new ClientApp(cmdParam);
        if (cmdParam.isInteractive()) {
            clientApp.startClientApp();
        } else {
            clientApp.sendSingleRequest();
        }
    }

    private final CliCmdParameters cmdParam;
    private final AppContext appCtx;
    private final URL remoteUrl;
    private final MslProperties prop;
    private final String entityId;
    private Client client;
    private MessageConfig cfg;
    private String currentUserId;

    private ClientApp(final CliCmdParameters cmdParam) throws Exception {

        this.cmdParam = cmdParam;

        // set server URL
        this.remoteUrl = cmdParam.getUrl();

        this.prop = MslProperties.getInstance(SharedUtil.loadPropertiesFromFile(cmdParam.getConfigFilePath()));
        this.appCtx = AppContext.getInstance(prop, cmdParam.getEntityId());

        /* second command-line argument with any value turns on diagnostic messages in MslControl
         */
        if (cmdParam.isVerbose()) {
            appCtx.getMslControl().setFilterFactory(new ConsoleFilterStreamFactory());
        }

        // initialize MSL Store - use wrapper to intercept selected MSL Store calls
        this.appCtx.setMslStoreWrapper(new AppMslStoreWrapper());

        this.entityId = cmdParam.getEntityId();

        this.currentUserId = cmdParam.getUserId();

        cfg = new MessageConfig();
        cfg.userId = currentUserId;
        cfg.isEncrypted = cmdParam.isEncrypted();
        cfg.isIntegrityProtected = cmdParam.isIntegrityProtected();
        cfg.isNonReplayable = cmdParam.isNonReplayable();
    }

    private void sendSingleRequest() throws Exception {
        client = new Client(appCtx, entityId);
        client.setUserAuthenticationDataHandle(new AppUserAuthenticationDataHandle());
        final String kx = cmdParam.getKeyExchangeScheme();
        if (kx != null) {
            String kxm = null;
            if (KX_AWE.equals(kx)) {
                cmdParam.getKeyExchangeMechanism();
            }
            client.setKeyRequestData(kx, kxm);
        }
        final String inputFile = cmdParam.getPayloadInputFile();
        final byte[] requestPayload = SharedUtil.readFromFile(inputFile);
        final String outputFile = cmdParam.getPayloadOutputFile();
        final Client.Response response = sendMessage(client, cfg, remoteUrl, requestPayload);
        if (response.getPayload() != null) {
             SharedUtil.saveToFile(outputFile, response.getPayload());
        }
    }

    private void startClientApp() throws Exception {
        client = new Client(appCtx, entityId);
        client.setUserAuthenticationDataHandle(new AppUserAuthenticationDataHandle());
        String cmd;
        while (!QUIT.equalsIgnoreCase(cmd = SharedUtil.readInput(String.format("Command(\"%s\" to exit) %s", QUIT, supportedCommands.toString())))) {
            if (CMD_KX.equalsIgnoreCase(cmd)) {
                setKeyExchange(client);
            } else if (CMD_CFG.equalsIgnoreCase(cmd)) {
                setConfig(cfg);
            } else if (CMD_MSG.equalsIgnoreCase(cmd)) {
                sendMessages(client, cfg, remoteUrl);
            } else if (CMD_HELP.equalsIgnoreCase(cmd)) {
                System.out.println(help());
            }
        }
    }

    /*
     * This class facilitates on-demand fetching of user authentication data
     */
    private final class AppUserAuthenticationDataHandle implements UserAuthenticationDataHandle {
        @Override
        public UserAuthenticationData getUserAuthenticationData() {
            System.out.println("UserAuthentication Data requested");
            if (cfg.userId != null) {
                final Pair<String,String> ep = prop.getEmailPassword(cfg.userId);
                return new EmailPasswordAuthenticationData(ep.x, ep.y);
            } else {
                return null;
            }
        }
    }

    /*
     * This is a listener to all MslStore calls. It can override only the methods in ClientStoreInterceptor the app cares about.
     */
    private final class AppMslStoreWrapper extends MslStoreWrapper {
        @Override
        public void setCryptoContext(final MasterToken masterToken, final ICryptoContext cryptoContext) {
            if (masterToken == null) {
                System.out.println("\nMslStore: setting crypto context with NULL MasterToken???");
            } else {
                System.out.println(String.format("\nMslStore: %s %s\n",
                    (cryptoContext != null)? "Adding" : "Removing", SharedUtil.getMasterTokenInfo(masterToken)));
            }
            super.setCryptoContext(masterToken, cryptoContext);
        }

        @Override
        public void removeCryptoContext(final MasterToken masterToken) {
            System.out.println("\nMslStore: Removing Crypto Context for " + SharedUtil.getMasterTokenInfo(masterToken));
            super.removeCryptoContext(masterToken);
        }

        @Override
        public void clearCryptoContexts() {
            System.out.println("\nMslStore: Clear Crypto Contexts");
            super.clearCryptoContexts();
        }

        @Override
        public void addUserIdToken(final String userId, final UserIdToken userIdToken) throws MslException {
            System.out.println(String.format("\nMslStore: Adding %s for userId %s", SharedUtil.getUserIdTokenInfo(userIdToken), userId));
            super.addUserIdToken(userId, userIdToken);
        }

        @Override
        public void removeUserIdToken(final UserIdToken userIdToken) {
            System.out.println("\nMslStore: Removing " + SharedUtil.getUserIdTokenInfo(userIdToken));
            super.removeUserIdToken(userIdToken);
        }

        @Override
        public UserIdToken getUserIdToken(final String userId) {
            System.out.println("\nMslStore: Getting UserIdToken for user ID " + userId);
            return super.getUserIdToken(userId);
        }
    }

    private static String help() {
        final String pad = "    ";
        final StringBuilder sb = new StringBuilder(); 
            sb.append("commands:\n")
              .append(CMD_KX).append(" - select Key Exchange Type\n")
              .append(pad).append(KX_DH).append(" - Diffie-Hellman Key Exchange\n")
              .append(pad).append(KX_SWE).append(" - Symmetric Wrapped Key Exchange\n")
              .append(pad).append(KX_AWE).append(" - Awymmetric Wrapped Key Exchange\n")
              .append(pad).append(pad).append("Mechanisms:\n")
              .append(pad).append(pad).append(AsymmetricWrappedExchange.RequestData.Mechanism.JWE_RSA).append(" - RSA-OAEP JSON Web Encryption JSON Serialization\n")
              .append(pad).append(pad).append(AsymmetricWrappedExchange.RequestData.Mechanism.JWEJS_RSA).append(" - RSA-OAEP JSON Web Encryption Compact Serialization\n")
              .append(pad).append(pad).append(AsymmetricWrappedExchange.RequestData.Mechanism.JWK_RSA).append(" - RSA-OAEP JSON Web Key\n")
              .append(pad).append(pad).append(AsymmetricWrappedExchange.RequestData.Mechanism.JWK_RSAES).append("- RSA PKCS#1 JSON Web Key\n")
              .append(pad).append(KX_JWEL).append(" - JSON Web Encryption Ladder Key Exchange\n")
              .append(pad).append(KX_JWKL).append(" - JSON Web Key Ladder Key Exchange\n")
              .append(CMD_CFG).append(" - set message properties:\n")
              .append(pad).append(USER_ID_PROMPT).append(" - User Id\n")
              .append(pad).append(MSG_ENCRYPTION_PROMPT).append(" - Encryption ON/OFF\n")
              .append(pad).append(MSG_INTEGRITY_PROMPT).append(" - Integrity Protection ON/OFF\n")
              .append(pad).append(MSG_NONREPLAY_PROMPT).append(" - Non-Replay ON/OFF\n")
              .append(CMD_MSG).append(" - send multiple text messages using the selected key exchange mechanism and message security properties\n")
              .append(pad).append("enter \"q\" to go back to the command menu\n");
        return sb.toString();
    }

    /*
     * Set client's key exchange data. Some key exchange types require setting specific mechanism.
     * Real-life clients may support just one key exchange type.
     *
     * This method may need to be called repetitively for key exchanges requiring ephemeral keys.
     */
    private static void setKeyExchange(final Client client) throws IOException, MslException {
        String kxType;
        while (!QUIT.equalsIgnoreCase(kxType = SharedUtil.readInput(String.format("KeyExchange(\"%s\" to skip) %s", QUIT, supportedKxTypes.toString())))) {
            if (supportedKxTypes.contains(kxType)) {
                String mechanism = null;
                if (KX_AWE.equals(kxType)) {
                    do {
                        mechanism = SharedUtil.readInput(String.format("Mechanism%s", supportedAsymmetricWrappedExchangeMechanisms.toString()));
                    } while (!supportedAsymmetricWrappedExchangeMechanisms.contains(mechanism));
                }
                client.setKeyRequestData(kxType, mechanism);
                break;
            }
        }
    }

    /*
     * Set message security basic properties, such as encryption, integrity protection, and non-renewability.
     * Real-life apps may set different parameters for each message, depending on security requirements.
     * This is why MessageConfig instance is passed in every Client.sendrequest() call.
     */
    private static void setConfig(final MessageConfig cfg) throws IOException {
        cfg.userId               = SharedUtil.readParameter(USER_ID_PROMPT, cfg.userId);
        cfg.isEncrypted          = SharedUtil.readBoolean(MSG_ENCRYPTION_PROMPT, cfg.isEncrypted         , YES, NO);
        cfg.isIntegrityProtected = SharedUtil.readBoolean(MSG_INTEGRITY_PROMPT , cfg.isIntegrityProtected, YES, NO);
        cfg.isNonReplayable      = SharedUtil.readBoolean(MSG_NONREPLAY_PROMPT , cfg.isNonReplayable     , YES, NO);
        System.out.println(cfg.toString());
    }

    /*
     * Send multiple text messages, using selected target URL, message configuration, and key exchange mechanism.
     */
    private static void sendMessages(final Client client, final MessageConfig cfg, final URL remoteUrl)
        throws ExecutionException, InterruptedException, IOException, MslException
    {
        System.out.println(cfg.toString());
        String msg;
        while (!QUIT.equalsIgnoreCase(msg = SharedUtil.readInput(String.format("Message(\"%s\" to finish)", QUIT)))) {
            Client.Response response = sendMessage(client, cfg, remoteUrl, msg.getBytes());
        }
    }

    /*
     * Send single message
     */
    private static Client.Response sendMessage(final Client client, final MessageConfig cfg, final URL remoteUrl, final byte[] payload)
        throws ExecutionException, InterruptedException, IOException, MslException
    {
        try {
            final Client.Response response = client.sendRequest(payload, cfg, remoteUrl);
            if (response.getPayload() != null) {
                System.out.println("Response: " + new String(response.getPayload()));
            } else if (response.getErrorHeader() != null) {
                if (response.getErrorHeader().getErrorMessage() != null) {
                    System.err.println(String.format("ERROR: error_code %d, error_msg \"%s\"", response.getErrorHeader().getErrorCode().intValue(),
                                                                                               response.getErrorHeader().getErrorMessage()));
                } else {
                    System.err.println(String.format("ERROR: %s" + response.getErrorHeader().toJSONString()));
                }
            } else {
                System.out.println("Internal ERROR: invalid response returned");
            }
            return response;
        } catch (Exception e) {
            System.err.println("Sending Error:");
            e.printStackTrace(System.err);
            throw e;
        }
    }
}
