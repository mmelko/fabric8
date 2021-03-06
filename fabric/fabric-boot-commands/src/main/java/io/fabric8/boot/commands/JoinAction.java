/**
 *  Copyright 2005-2016 Red Hat, Inc.
 *
 *  Red Hat licenses this file to you under the Apache License, version
 *  2.0 (the "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 *  implied.  See the License for the specific language governing
 *  permissions and limitations under the License.
 */
package io.fabric8.boot.commands;

import static io.fabric8.zookeeper.utils.ZooKeeperUtils.exists;
import io.fabric8.api.Constants;
import io.fabric8.api.ContainerOptions;
import io.fabric8.api.FabricConstants;
import io.fabric8.api.RuntimeProperties;
import io.fabric8.api.SystemProperties;
import io.fabric8.api.ZkDefs;
import io.fabric8.utils.BundleUtils;
import io.fabric8.utils.FabricValidations;
import io.fabric8.utils.PasswordEncoder;
import io.fabric8.utils.Ports;
import io.fabric8.utils.shell.ShellUtils;
import io.fabric8.zookeeper.ZkPath;
import io.fabric8.zookeeper.bootstrap.BootstrapConfiguration;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Dictionary;
import java.util.Hashtable;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.RetryOneTime;
import org.apache.felix.gogo.commands.Argument;
import org.apache.felix.gogo.commands.Command;
import org.apache.felix.gogo.commands.Option;
import org.apache.felix.utils.properties.Properties;
import org.apache.karaf.shell.console.AbstractAction;
import org.apache.zookeeper.KeeperException;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;

@Command(name = "join", scope = "fabric", description = "Join a container to an existing fabric", detailedDescription = "classpath:join.txt")
final class JoinAction extends AbstractAction {

    @Option(name = "-n", aliases = "--non-managed", multiValued = false, description = "Flag to keep the container non managed")
    private boolean nonManaged;

    @Option(name = "-f", aliases = "--force", multiValued = false, description = "Forces the use of container name")
    private boolean force;

    @Option(name = "-p", aliases = "--profile", multiValued = false, description = "Chooses the profile of the container")
    private String profile = "fabric";

    @Option(name = "-v", aliases = "--version", multiValued = false, description = "Chooses the version of the container.")
    private String version = ContainerOptions.DEFAULT_VERSION;

    @Option(name = "--min-port", multiValued = false, description = "The minimum port of the allowed port range")
    private int minimumPort = Ports.MIN_PORT_NUMBER;

    @Option(name = "--max-port", multiValued = false, description = "The maximum port of the allowed port range")
    private int maximumPort = Ports.MAX_PORT_NUMBER;

    @Argument(required = true, index = 0, multiValued = false, description = "Zookeeper URL")
    private String zookeeperUrl;

    @Option(name = "-r", aliases = {"--resolver"}, description = "The resolver policy. Possible values are: localip, localhostname, publicip, publichostname, manualip. Default is localhostname.")
    String resolver;

    @Option(name = "-b", aliases = {"--bind-address"}, description = "The default bind address.")
    String bindAddress;

    @Option(name = "-m", aliases = {"--manual-ip"}, description = "An address to use, when using the manualip resolver.")
    String manualIp;

    @Option(name = "--zookeeper-password", multiValued = false, description = "The ensemble password to use.")
    private String zookeeperPassword;

    @Argument(required = false, index = 1, multiValued = false, description = "Container name to use in fabric. By default a karaf name will be used")
    private String containerName;

    private final ConfigurationAdmin configAdmin;
    private final BundleContext bundleContext;
    private final RuntimeProperties runtimeProperties;

    JoinAction(BundleContext bundleContext, ConfigurationAdmin configAdmin, RuntimeProperties runtimeProperties) {
        this.configAdmin = configAdmin;
        this.bundleContext = bundleContext;
        this.runtimeProperties = runtimeProperties;
    }

    @Override
    protected Object doExecute() throws Exception {
        if (nonManaged) {
            profile = "unmanaged";
        }
        String oldName = runtimeProperties.getRuntimeIdentity();

        if( System.getenv("OPENSHIFT_BROKER_HOST")!=null && containerName!=null ) {
            System.err.println("Containers in OpenShift cannot be renamed");
            return null;
        }

        if (containerName == null) {
            containerName = oldName;
        }

        FabricValidations.validateContainerName(containerName);
        Configuration bootConfiguration = configAdmin.getConfiguration(BootstrapConfiguration.COMPONENT_PID, null);
        Configuration configZook = configAdmin.getConfiguration(Constants.ZOOKEEPER_CLIENT_PID, null);

        if (configZook.getProperties() != null && configZook.getProperties().get("zookeeper.url") != null) {
           System.err.println("This container is already connected to a fabric");
           return null;
        }
        Dictionary<String, Object> bootProperties = bootConfiguration.getProperties();
        if (bootProperties == null) {
            bootProperties = new Hashtable<>();
        }

        if (resolver != null) {
            bootProperties.put(ZkDefs.LOCAL_RESOLVER_PROPERTY, resolver);
        }

        if (manualIp != null) {
            bootProperties.put(ZkDefs.MANUAL_IP, manualIp);
        }

        if (bindAddress != null) {
            bootProperties.put(ZkDefs.BIND_ADDRESS, bindAddress);
        }

        zookeeperPassword = zookeeperPassword != null ? zookeeperPassword : ShellUtils.retrieveFabricZookeeperPassword(session);

        if (zookeeperPassword == null) {
            zookeeperPassword = promptForZookeeperPassword();
        }

        if (zookeeperPassword == null || zookeeperPassword.isEmpty()) {
            System.out.println("No password specified. Cannot join fabric ensemble.");
            return null;
        }
        ShellUtils.storeZookeeperPassword(session, zookeeperPassword);

        log.debug("Encoding ZooKeeper password.");
        String encodedPassword = PasswordEncoder.encode(zookeeperPassword);

        bootProperties.put(ZkDefs.MINIMUM_PORT, String.valueOf(minimumPort));
        bootProperties.put(ZkDefs.MAXIMUM_PORT, String.valueOf(maximumPort));

        if (!containerName.equals(oldName)) {
            if (force || permissionToRenameContainer()) {
                if (!registerContainer(containerName, zookeeperPassword, profile, force)) {
                    System.err.println("A container with the name: " + containerName + " is already member of the cluster. You can specify a different name as an argument.");
                    return null;
                }

                bootProperties.put(SystemProperties.KARAF_NAME, containerName);
                //Ensure that if we bootstrap CuratorFramework via RuntimeProperties password is set before the URL.
                bootProperties.put("zookeeper.password", encodedPassword);
                bootProperties.put("zookeeper.url", zookeeperUrl);
                //Rename the container
                Path propsPath = runtimeProperties.getConfPath().resolve("system.properties");
                Properties systemProps = new Properties(propsPath.toFile());
                systemProps.put(SystemProperties.KARAF_NAME, containerName);
                //Also pass zookeeper information so that the container can auto-join after the restart.
                systemProps.put("zookeeper.url", zookeeperUrl);
                systemProps.put("zookeeper.password", encodedPassword);
                systemProps.save();

                System.setProperty("runtime.id", containerName);
                System.setProperty(SystemProperties.KARAF_NAME, containerName);
                System.setProperty("karaf.restart", "true");
                System.setProperty("karaf.restart.clean", "false");

                if (!nonManaged) {
                    installBundles();
                }
                //Restart the container
                bootConfiguration.update(bootProperties);
                bundleContext.getBundle(0).stop();

                return null;
            } else {
                return null;
            }
        } else {
            bootConfiguration.update(bootProperties);
            if (!registerContainer(containerName, zookeeperPassword, profile, force)) {
                System.err.println("A container with the name: " + containerName + " is already member of the cluster. You can specify a different name as an argument.");
                return null;
            }
            Configuration config = configAdmin.getConfiguration(Constants.ZOOKEEPER_CLIENT_PID, null);
            Hashtable<String, Object> properties = new Hashtable<String, Object>();
            properties.put("zookeeper.url", zookeeperUrl);
            properties.put("zookeeper.password", PasswordEncoder.encode(encodedPassword));
            config.setBundleLocation(null);
            config.update(properties);
            if (!nonManaged) {
                installBundles();
            }
            return null;
        }
    }

    private String promptForZookeeperPassword() throws IOException {
        String password = ShellUtils.readLine(session, "Ensemble password: ", true);

        return password;
    }

    /**
     * Checks if there is an existing container using the same name.
     *
     * @param name
     * @return
     * @throws InterruptedException
     * @throws KeeperException
     */
    private boolean registerContainer(String name, String registryPassword, String profile, boolean force) throws Exception {
        boolean exists = false;
        CuratorFramework curator = null;
        try {

            CuratorFrameworkFactory.Builder builder = CuratorFrameworkFactory.builder()
                    .connectString(zookeeperUrl)
                    .retryPolicy(new RetryOneTime(1000))
                    .connectionTimeoutMs(60000);

            if (registryPassword != null && !registryPassword.isEmpty()) {
                builder.authorization("digest", ("fabric:" + registryPassword).getBytes());
            }

            curator = builder.build();
            curator.start();
            curator.getZookeeperClient().blockUntilConnectedOrTimedOut();
            exists = exists(curator, ZkPath.CONTAINER.getPath(name)) != null;
            if (!exists || force) {
                ZkPath.createContainerPaths(curator, containerName, version, profile);
            }
        } finally {
            if (curator != null) {
                curator.close();
            }
        }
        return !exists || force;
    }

    /**
     * Asks the users permission to restart the container.
     *
     * @return
     * @throws IOException
     */
    private boolean permissionToRenameContainer() throws IOException {
        System.err.println("You are about to change the container name. This action will restart the container.");
        System.err.println("The local shell will automatically restart, but ssh connections will be terminated.");
        System.err.println("The container will automatically join: " + zookeeperUrl + " the cluster after it restarts.");
        System.err.flush();

        String response = ShellUtils.readLine(session, "Do you wish to proceed (yes/no): ", false);
        return response != null && (response.toLowerCase().equals("yes") || response.toLowerCase().equals("y"));
    }

    public void installBundles() throws BundleException {
        BundleUtils bundleUtils = new BundleUtils(bundleContext);
        Bundle bundleFabricCommands = bundleUtils.findBundle("io.fabric8.fabric-commands");
        if (bundleFabricCommands == null) {
            bundleFabricCommands = bundleUtils.installBundle("mvn:io.fabric8/fabric-commands/" + FabricConstants.FABRIC_VERSION);
        }
        bundleFabricCommands.start();
        Bundle bundleFabricAgent = bundleUtils.findBundle("io.fabric8.fabric-agent");

        if (nonManaged && bundleFabricAgent == null) {
            //do nothing
        } else if (nonManaged && bundleFabricAgent != null) {
            bundleFabricAgent.stop();
        } else if (bundleFabricAgent == null) {
            bundleFabricAgent = bundleUtils.installBundle("mvn:io.fabric8/fabric-agent/" + FabricConstants.FABRIC_VERSION);
            bundleFabricAgent.start();
        } else {
            bundleFabricAgent.start();
        }
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getZookeeperUrl() {
        return zookeeperUrl;
    }

    public void setZookeeperUrl(String zookeeperUrl) {
        this.zookeeperUrl = zookeeperUrl;
    }

    public boolean isNonManaged() {
        return nonManaged;
    }

    public void setNonManaged(boolean nonManaged) {
        this.nonManaged = nonManaged;
    }

    public boolean isForce() {
        return force;
    }

    public void setForce(boolean force) {
        this.force = force;
    }

    public String getProfile() {
        return profile;
    }

    public void setProfile(String profile) {
        this.profile = profile;
    }

    public String getContainerName() {
        return containerName;
    }

    public void setContainerName(String containerName) {
        this.containerName = containerName;
    }

    public String getResolver() {
        return resolver;
    }

    public void setResolver(String resolver) {
        this.resolver = resolver;
    }

    public String getManualIp() {
        return manualIp;
    }

    public void setManualIp(String manualIp) {
        this.manualIp = manualIp;
    }

    public String getZookeeperPassword() {
        return zookeeperPassword;
    }

    public void setZookeeperPassword(String zookeeperPassword) {
        this.zookeeperPassword = zookeeperPassword;
    }
}
