/*
 * Copyright (C) 2010 Toni Menzel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.fabric8.maven.url.internal;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import io.fabric8.maven.DependencyFilters;
import io.fabric8.maven.DuplicateTransformer;
import io.fabric8.maven.FailedToResolveDependency;
import io.fabric8.maven.MavenResolver;
import io.fabric8.maven.PomDetails;
import io.fabric8.maven.ReplaceConflictingVersionResolver;
import io.fabric8.maven.StaticWagonProvider;
import io.fabric8.common.util.Filter;
import io.fabric8.common.util.Filters;
import io.fabric8.common.util.IOHelpers;
import io.fabric8.maven.util.MavenConfiguration;
import io.fabric8.maven.util.MavenRepositoryURL;
import io.fabric8.maven.util.Parser;
import io.fabric8.maven.util.decrypt.MavenSettingsDecrypter;
import org.apache.maven.model.Model;
import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.apache.maven.settings.Mirror;
import org.apache.maven.settings.Server;
import org.apache.maven.settings.Settings;
import org.apache.maven.settings.crypto.DefaultSettingsDecryptionRequest;
import org.apache.maven.settings.crypto.SettingsDecrypter;
import org.apache.maven.settings.crypto.SettingsDecryptionRequest;
import org.apache.maven.settings.crypto.SettingsDecryptionResult;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.eclipse.aether.ConfigurationProperties;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositoryException;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.ArtifactProperties;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.artifact.DefaultArtifactType;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.collection.DependencyCollectionContext;
import org.eclipse.aether.collection.DependencyCollectionException;
import org.eclipse.aether.collection.DependencyGraphTransformationContext;
import org.eclipse.aether.collection.DependencyGraphTransformer;
import org.eclipse.aether.collection.DependencyManager;
import org.eclipse.aether.collection.DependencySelector;
import org.eclipse.aether.collection.DependencyTraverser;
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory;
import org.eclipse.aether.graph.DefaultDependencyNode;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyFilter;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.impl.DefaultServiceLocator;
import org.eclipse.aether.internal.impl.DefaultTransporterProvider;
import org.eclipse.aether.internal.impl.slf4j.Slf4jLoggerFactory;
import org.eclipse.aether.repository.Authentication;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.MirrorSelector;
import org.eclipse.aether.repository.Proxy;
import org.eclipse.aether.repository.ProxySelector;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.repository.RepositoryPolicy;
import org.eclipse.aether.resolution.ArtifactDescriptorRequest;
import org.eclipse.aether.resolution.ArtifactDescriptorResult;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResolutionException;
import org.eclipse.aether.resolution.VersionRangeRequest;
import org.eclipse.aether.resolution.VersionRangeResolutionException;
import org.eclipse.aether.resolution.VersionRangeResult;
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory;
import org.eclipse.aether.spi.connector.transport.TransporterFactory;
import org.eclipse.aether.spi.connector.transport.TransporterProvider;
import org.eclipse.aether.transport.wagon.WagonProvider;
import org.eclipse.aether.transport.wagon.WagonTransporterFactory;
import org.eclipse.aether.util.artifact.DefaultArtifactTypeRegistry;
import org.eclipse.aether.util.graph.manager.ClassicDependencyManager;
import org.eclipse.aether.util.graph.selector.AndDependencySelector;
import org.eclipse.aether.util.graph.selector.ExclusionDependencySelector;
import org.eclipse.aether.util.graph.selector.OptionalDependencySelector;
import org.eclipse.aether.util.graph.selector.ScopeDependencySelector;
import org.eclipse.aether.util.graph.transformer.ChainedDependencyGraphTransformer;
import org.eclipse.aether.util.graph.transformer.ConflictResolver;
import org.eclipse.aether.util.graph.transformer.JavaDependencyContextRefiner;
import org.eclipse.aether.util.graph.transformer.JavaScopeDeriver;
import org.eclipse.aether.util.graph.transformer.JavaScopeSelector;
import org.eclipse.aether.util.graph.transformer.NearestVersionSelector;
import org.eclipse.aether.util.graph.transformer.SimpleOptionalitySelector;
import org.eclipse.aether.util.graph.traverser.FatArtifactTraverser;
import org.eclipse.aether.util.repository.AuthenticationBuilder;
import org.eclipse.aether.util.repository.DefaultMirrorSelector;
import org.eclipse.aether.util.repository.DefaultProxySelector;
import org.eclipse.aether.util.repository.SimpleArtifactDescriptorPolicy;
import org.eclipse.aether.util.version.GenericVersionScheme;
import org.eclipse.aether.version.InvalidVersionSpecificationException;
import org.eclipse.aether.version.Version;
import org.eclipse.aether.version.VersionConstraint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.fabric8.maven.util.Parser.VERSION_LATEST;

/**
 * Aether based, drop in replacement for mvn protocol
 */
public class AetherBasedResolver implements MavenResolver {

    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(AetherBasedResolver.class);
    private static final String LATEST_VERSION_RANGE = "(0.0,]";
    private static final String REPO_TYPE = "default";

    private static final Logger LOGGER = LoggerFactory.getLogger(AetherBasedResolver.class);

    final private RepositorySystem m_repoSystem;
    final private MavenConfiguration m_config;
    final private MirrorSelector m_mirrorSelector;
    final private ProxySelector m_proxySelector;
    private Settings m_settings;
    private SettingsDecrypter decrypter;

    private LocalRepository localRepository;
    private final ConcurrentMap<LocalRepository, Deque<DefaultRepositorySystemSession>> sessions
            = new ConcurrentHashMap<LocalRepository, Deque<DefaultRepositorySystemSession>>();

    /**
     * Create a AetherBasedResolver
     *
     * @param configuration (must be not null)
     */
    public AetherBasedResolver( final MavenConfiguration configuration ) {
        this( configuration, null, null );
    }

    /**
     * Create a AetherBasedResolver
     * 
     * @param configuration (must be not null)
     */
    public AetherBasedResolver( final MavenConfiguration configuration, final Mirror mirror ) {
        this( configuration, mirror, null );
    }

    /**
     * Create a AetherBasedResolver
     *
     * @param configuration (must be not null)
     */
    public AetherBasedResolver( final MavenConfiguration configuration, final Mirror mirror, final RepositorySystem repositorySystem ) {
        m_config = configuration;
        m_settings = configuration.getSettings();
        if (repositorySystem == null) {
            m_repoSystem = newRepositorySystem();
        } else {
            m_repoSystem = repositorySystem;
            decrypter = new MavenSettingsDecrypter( m_config.getSecuritySettings() );
        }
        decryptSettings();
        m_proxySelector = selectProxies();
        m_mirrorSelector = selectMirrors( mirror );
    }

    private RepositorySystem newRepositorySystem() {
        DefaultServiceLocator locator = MavenRepositorySystemUtils.newServiceLocator();

        locator.addService( RepositoryConnectorFactory.class, BasicRepositoryConnectorFactory.class );
        locator.addService( TransporterProvider.class, DefaultTransporterProvider.class );
        locator.addService( TransporterFactory.class, WagonTransporterFactory.class );
        locator.setServices( WagonProvider.class, new StaticWagonProvider(m_config.getTimeout()) );
        locator.addService( org.eclipse.aether.spi.log.LoggerFactory.class, Slf4jLoggerFactory.class );

        decrypter = new MavenSettingsDecrypter( m_config.getSecuritySettings() );
        locator.setServices( SettingsDecrypter.class, decrypter );
        return locator.getService(RepositorySystem.class);
    }

    private void decryptSettings()
    {
        SettingsDecryptionRequest request = new DefaultSettingsDecryptionRequest( m_settings );
        SettingsDecryptionResult result = decrypter.decrypt( request );
        m_settings.setProxies(result.getProxies());
        m_settings.setServers( result.getServers() );
    }

    private ProxySelector selectProxies() {
        DefaultProxySelector proxySelector = new DefaultProxySelector();
        for( org.apache.maven.settings.Proxy proxy : m_settings.getProxies() ) {
            String nonProxyHosts = proxy.getNonProxyHosts();
            Proxy proxyObj = new Proxy( proxy.getProtocol(), proxy.getHost(), proxy.getPort(),
                    getAuthentication( proxy ) );
            proxySelector.add( proxyObj, nonProxyHosts );
        }
        return proxySelector;
    }

    private MirrorSelector selectMirrors( Mirror mirror ) {
        // configure mirror
        DefaultMirrorSelector selector = new DefaultMirrorSelector();
        for( Mirror m : m_settings.getMirrors() ) {
            selector.add( m.getName(), m.getUrl(), null, false, m.getMirrorOf(), "*" );
        }
        if( mirror != null ) {
            selector.add(mirror.getName(), mirror.getUrl(), null, false, mirror.getMirrorOf(), "*");
        }
        return selector;
    }

    @Override
    public RepositorySystem getRepositorySystem() {
        return m_repoSystem;
    }

    @Override
    public List<RemoteRepository> getRepositories() {
        List<RemoteRepository> repos = selectRepositories();
        assignProxyAndMirrors(repos);
        return repos;
    }

    private List<RemoteRepository> selectRepositories() {
        List<RemoteRepository> list = new ArrayList<RemoteRepository>();
        List<MavenRepositoryURL> urls = Collections.emptyList();
        try {
            urls = m_config.getRepositories();
        }
        catch( MalformedURLException exc ) {
            LOG.error( "invalid repository URLs", exc );
        }
        for( MavenRepositoryURL r : urls ) {
            if( r.isMulti() ) {
                addSubDirs( list, r.getFile() );
            }
            else {
                addRepo( list, r );
            }
        }
        
        return list;
    }

    private void assignProxyAndMirrors( List<RemoteRepository> remoteRepos ) {
        Map<String, List<String>> map = new HashMap<>();
        Map<String, RemoteRepository> naming = new HashMap<>();

        List<RemoteRepository> resultingRepos = new ArrayList<>();

        for( RemoteRepository r : remoteRepos ) {
            naming.put( r.getId(), r );

            RemoteRepository rProxy = new RemoteRepository.Builder( r ).setProxy(
                    m_proxySelector.getProxy( r ) ).build();
            resultingRepos.add( rProxy );

            RemoteRepository mirror = m_mirrorSelector.getMirror( r );
            if( mirror != null ) {
                String key = mirror.getId();
                naming.put( key, mirror );
                if( !map.containsKey( key ) ) {
                    map.put( key, new ArrayList<String>() );
                }
                List<String> mirrored = map.get( key );
                mirrored.add(r.getId());
            }
        }

        for( String mirrorId : map.keySet() ) {
            RemoteRepository mirror = naming.get( mirrorId );
            List<RemoteRepository> mirroredRepos = new ArrayList<RemoteRepository>();

            for( String rep : map.get( mirrorId ) ) {
                mirroredRepos.add( naming.get( rep ) );
            }
            mirror = new RemoteRepository.Builder( mirror ).setMirroredRepositories( mirroredRepos )
                    .setProxy( m_proxySelector.getProxy( mirror ) )
                    .build();
            resultingRepos.removeAll( mirroredRepos );
            resultingRepos.add( 0, mirror );
        }

        remoteRepos.clear();
        remoteRepos.addAll( resultingRepos );
    }

    List<LocalRepository> selectDefaultRepositories() {
        List<LocalRepository> list = new ArrayList<LocalRepository>();
        List<MavenRepositoryURL> urls = Collections.emptyList();
        try {
            urls = m_config.getDefaultRepositories();
        }
        catch( MalformedURLException exc ) {
            LOG.error( "invalid repository URLs", exc );
        }
        for( MavenRepositoryURL r : urls ) {
            if( r.isMulti() ) {
                addLocalSubDirs(list, r.getFile());
            }
            else {
                addLocalRepo(list, r);
            }
        }

        return list;
    }

    private void addSubDirs( List<RemoteRepository> list, File parentDir ) {
        if( !parentDir.isDirectory() ) {
            LOG.debug( "Repository marked with @multi does not resolve to a directory: "
                + parentDir );
            return;
        }
        for( File repo : parentDir.listFiles() ) {
            if( repo.isDirectory() ) {
                try {
                    String repoURI = repo.toURI().toString() + "@id=" + repo.getName();
                    LOG.debug( "Adding repo from inside multi dir: " + repoURI );
                    addRepo( list, new MavenRepositoryURL( repoURI ) );
                }
                catch( MalformedURLException e ) {
                    LOG.error( "Error resolving repo url of a multi repo " + repo.toURI() );
                }
            }
        }
    }

    private void addRepo( List<RemoteRepository> list, MavenRepositoryURL repo ) {
        String releasesUpdatePolicy = repo.getReleasesUpdatePolicy();
        if (releasesUpdatePolicy == null || releasesUpdatePolicy.isEmpty()) {
            releasesUpdatePolicy = new RepositoryPolicy().getUpdatePolicy();
        }
        String releasesChecksumPolicy = repo.getReleasesChecksumPolicy();
        if (releasesChecksumPolicy == null || releasesChecksumPolicy.isEmpty()) {
            releasesChecksumPolicy = new RepositoryPolicy().getChecksumPolicy();
        }
        String snapshotsUpdatePolicy = repo.getSnapshotsUpdatePolicy();
        if (snapshotsUpdatePolicy == null || snapshotsUpdatePolicy.isEmpty()) {
            snapshotsUpdatePolicy = new RepositoryPolicy().getUpdatePolicy();
        }
        String snapshotsChecksumPolicy = repo.getSnapshotsChecksumPolicy();
        if (snapshotsChecksumPolicy == null || snapshotsChecksumPolicy.isEmpty()) {
            snapshotsChecksumPolicy = new RepositoryPolicy().getChecksumPolicy();
        }
        RemoteRepository.Builder builder = new RemoteRepository.Builder( repo.getId(), REPO_TYPE, repo.getURL().toExternalForm() );
        RepositoryPolicy releasePolicy = new RepositoryPolicy( repo.isReleasesEnabled(), releasesUpdatePolicy, releasesChecksumPolicy );
        builder.setReleasePolicy( releasePolicy );
        RepositoryPolicy snapshotPolicy = new RepositoryPolicy( repo.isSnapshotsEnabled(), snapshotsUpdatePolicy, snapshotsChecksumPolicy );
        builder.setSnapshotPolicy( snapshotPolicy );
        Authentication authentication = getAuthentication( repo.getId() );
        if (authentication != null) {
            builder.setAuthentication( authentication );
        }
        list.add( builder.build() );
    }

    private void addLocalSubDirs( List<LocalRepository> list, File parentDir ) {
        if( !parentDir.isDirectory() ) {
            LOG.debug( "Repository marked with @multi does not resolve to a directory: "
                    + parentDir );
            return;
        }
        for( File repo : parentDir.listFiles() ) {
            if( repo.isDirectory() ) {
                try {
                    String repoURI = repo.toURI().toString() + "@id=" + repo.getName();
                    LOG.debug( "Adding repo from inside multi dir: " + repoURI );
                    addLocalRepo(list, new MavenRepositoryURL(repoURI));
                }
                catch( MalformedURLException e ) {
                    LOG.error( "Error resolving repo url of a multi repo " + repo.toURI() );
                }
            }
        }
    }

    private void addLocalRepo( List<LocalRepository> list, MavenRepositoryURL repo ) {
        if (repo.getFile() != null) {
            LocalRepository local = new LocalRepository( repo.getFile(), "simple" );
            list.add( local );
        }
    }

    /**
     * Resolve maven artifact as input stream.
     */
    public InputStream resolve( String groupId, String artifactId, String classifier,
        String extension, String version ) throws IOException {
        File resolved = resolveFile( groupId, artifactId, classifier, extension, version );
        return new FileInputStream( resolved );
    }

    /**
     * Resolve maven artifact as file in repository.
     */
    public File resolveFile( String groupId, String artifactId, String classifier,
                             String extension, String version ) throws IOException {
        // version = mapLatestToRange( version );

        Artifact artifact = new DefaultArtifact( groupId, artifactId, classifier, extension, version );
        return resolveFile( artifact );
    }

    public File download(String url) throws IOException {
        Parser parser = Parser.parsePathWithSchemePrefix(url);
        return resolveFile(
                parser.getGroup(),
                parser.getArtifact(),
                parser.getClassifier(),
                parser.getType(),
                parser.getVersion(),
                parser.getRepositoryURL()
        );
    }

    /**
     * Resolve maven artifact as file in repository.
     */
    public File resolveFile( Artifact artifact ) throws IOException {
        return resolveFile( artifact, null );
    }

    /**
     * Resolve maven artifact as file in repository.
     */
    public File resolveFile( String groupId, String artifactId, String classifier,
                             String extension, String version,
                             MavenRepositoryURL repositoryURL ) throws IOException {
        Artifact artifact = new DefaultArtifact( groupId, artifactId, classifier, extension, version );
        return resolveFile( artifact, repositoryURL );
    }

    /**
     * Resolve maven artifact as file in repository.
     */
    public File resolveFile( Artifact artifact,
                             MavenRepositoryURL repositoryURL ) throws IOException {

        List<LocalRepository> defaultRepos = selectDefaultRepositories();
        List<RemoteRepository> remoteRepos = selectRepositories();
        if (repositoryURL != null) {
            addRepo(remoteRepos, repositoryURL);
        }
        assignProxyAndMirrors( remoteRepos );
        File resolved = resolve( defaultRepos, remoteRepos, artifact );

        LOG.debug( "Resolved ({}) as {}", artifact.toString(), resolved.getAbsolutePath() );
        return resolved;
    }

    private File resolve( List<LocalRepository> defaultRepos,
                          List<RemoteRepository> remoteRepos,
                          Artifact artifact ) throws IOException {
        if (artifact.getExtension().isEmpty()) {
            artifact = new DefaultArtifact(
                    artifact.getGroupId(),
                    artifact.getArtifactId(),
                    artifact.getClassifier(),
                    "jar",
                    artifact.getVersion()
            );
        }

        // Try with default repositories
        try {
            VersionConstraint vc = new GenericVersionScheme().parseVersionConstraint(artifact.getVersion());
            if (vc.getVersion() != null && !vc.getVersion().toString().endsWith("SNAPSHOT")) {
                for (LocalRepository repo : defaultRepos) {
                    DefaultRepositorySystemSession session = newSession( repo );
                    try {
                        return m_repoSystem
                                .resolveArtifact(session, new ArtifactRequest(artifact, null, null))
                                .getArtifact().getFile();
                    }
                    catch( ArtifactResolutionException e ) {
                        // Ignore
                    } finally {
                        releaseSession(session);
                    }
                }
            }
        }
        catch( InvalidVersionSpecificationException e ) {
            // Should not happen
        }

        DefaultRepositorySystemSession session = newSession(null);
        try {
            artifact = resolveLatestVersionRange( session, remoteRepos, artifact );
            ArtifactResult result = m_repoSystem
                    .resolveArtifact( session, new ArtifactRequest( artifact, remoteRepos, null ) );

            File resolved = result.getArtifact().getFile();
            LOG.debug( "Resolved ({}) as {}", artifact.toString(), resolved.getAbsolutePath() );
            return resolved;
        }
        catch( ArtifactResolutionException e ) {
            /**
             * Do not add root exception to avoid NotSerializableException on DefaultArtifact. To
             * avoid loosing information log the root cause. We can remove this again as soon as
             * DefaultArtifact is serializeable. See http://team.ops4j.org/browse/PAXURL-206
             */
            LOG.warn( "Error resolving artifact" + artifact.toString() + ":" + e.getMessage(), e );
            throw new IOException( "Error resolving artifact " + artifact.toString() + ": "
                    + e.getMessage() );
        }
        catch( RepositoryException e ) {
            throw new IOException( "Error resolving artifact " + artifact.toString(), e );
        }
        finally {
            releaseSession(session);
        }
    }

    /**
     * Tries to resolve versions = LATEST using an open range version query. If it succeeds, version
     * of artifact is set to the highest available version.
     * 
     * @param session
     *            to be used.
     * @param artifact
     *            to be used
     * 
     * @return an artifact with version set properly (highest if available)
     * 
     * @throws org.eclipse.aether.resolution.VersionRangeResolutionException
     *             in case of resolver errors.
     */
    private Artifact resolveLatestVersionRange( RepositorySystemSession session,
        List<RemoteRepository> remoteRepos, Artifact artifact )
        throws VersionRangeResolutionException {
        if( artifact.getVersion().equals( VERSION_LATEST ) ) {
            artifact = artifact.setVersion( LATEST_VERSION_RANGE );
        }

        VersionRangeResult versionResult = m_repoSystem.resolveVersionRange( session,
            new VersionRangeRequest( artifact, remoteRepos, null ) );
        if( versionResult != null ) {
            Version v = versionResult.getHighestVersion();
            if( v != null ) {
                
                artifact = artifact.setVersion( v.toString() );
            }
            else {
                throw new VersionRangeResolutionException( versionResult,
                    "No highest version found for " + artifact );
            }
        }
        return artifact;
    }

    public DefaultRepositorySystemSession newSession() {
        return newSession(null);
    }

    private DefaultRepositorySystemSession newSession(LocalRepository repo) {
        if (repo == null) {
            if (localRepository == null) {
                File local;
                if( m_config.getLocalRepository() != null ) {
                    local = m_config.getLocalRepository().getFile();
                } else {
                    local = new File( System.getProperty( "user.home" ), ".m2/repository" );
                }
                localRepository = new LocalRepository( local );
            }
            repo = localRepository;
        }
        Deque<DefaultRepositorySystemSession> deque = sessions.get(repo);
        DefaultRepositorySystemSession session = null;
        if (deque != null) {
            session = deque.pollFirst();
        }
        if (session == null) {
            session = createSession(repo);
        }
        return session;
    }

    private void releaseSession(DefaultRepositorySystemSession session) {
        LocalRepository repo = session.getLocalRepository();
        Deque<DefaultRepositorySystemSession> deque = sessions.get(repo);
        if (deque == null) {
            sessions.putIfAbsent(repo, new ConcurrentLinkedDeque<DefaultRepositorySystemSession>());
            deque = sessions.get(repo);
        }
        deque.add(session);
    }

    @Override
    public RepositorySystemSession createSession() {
        return createSession(null);
    }

    public DefaultRepositorySystemSession createSession(LocalRepository repo) {
        DefaultRepositorySystemSession session = newRepositorySystemSession();

        if( repo != null ) {
            session.setLocalRepositoryManager( m_repoSystem.newLocalRepositoryManager( session, repo ) );
        } else {
            File local;
            if (m_config.getLocalRepository() != null) {
                local = m_config.getLocalRepository().getFile();
            } else {
                local = new File(System.getProperty("user.home"), ".m2/repository");
            }
            LocalRepository localRepo = new LocalRepository(local);
            session.setLocalRepositoryManager(m_repoSystem.newLocalRepositoryManager(session, localRepo));
        }

        session.setMirrorSelector( m_mirrorSelector );
        session.setProxySelector( m_proxySelector );

        String updatePolicy = m_config.getGlobalUpdatePolicy();
        if( null != updatePolicy ) {
            session.setUpdatePolicy( updatePolicy );
        }

        String checksumPolicy = m_config.getGlobalChecksumPolicy();
        if( null != checksumPolicy ) {
            session.setChecksumPolicy(checksumPolicy);
        }

        for (Server server : m_settings.getServers()) {
            if (server.getConfiguration() != null
                && ((Xpp3Dom)server.getConfiguration()).getChild("httpHeaders") != null) {
                addServerConfig(session, server);
            }
        }

        session.setOffline( m_config.isOffline() );

        return session;
    }

    private static DefaultRepositorySystemSession newRepositorySystemSession()
    {
        DefaultRepositorySystemSession session = new DefaultRepositorySystemSession();

        DependencyTraverser depTraverser = new FatArtifactTraverser();
        session.setDependencyTraverser( depTraverser );

        DependencyManager depManager = new ClassicDependencyManager();
        session.setDependencyManager( depManager );

        DependencySelector depFilter =
                new AndDependencySelector( new ScopeDependencySelector( "test", "provided" ),
                        new OptionalDependencySelector(), new ExclusionDependencySelector() );
        session.setDependencySelector( depFilter );

        DependencyGraphTransformer transformer =
                new ConflictResolver( new NearestVersionSelector(), new JavaScopeSelector(),
                        new SimpleOptionalitySelector(), new JavaScopeDeriver() );
        new ChainedDependencyGraphTransformer( transformer, new JavaDependencyContextRefiner() );
        session.setDependencyGraphTransformer( transformer );

        DefaultArtifactTypeRegistry stereotypes = new DefaultArtifactTypeRegistry();
        stereotypes.add( new DefaultArtifactType( "pom" ) );
        stereotypes.add( new DefaultArtifactType( "maven-plugin", "jar", "", "java" ) );
        stereotypes.add( new DefaultArtifactType( "jar", "jar", "", "java" ) );
        stereotypes.add( new DefaultArtifactType( "ejb", "jar", "", "java" ) );
        stereotypes.add( new DefaultArtifactType( "ejb-client", "jar", "client", "java" ) );
        stereotypes.add( new DefaultArtifactType( "test-jar", "jar", "tests", "java" ) );
        stereotypes.add( new DefaultArtifactType( "javadoc", "jar", "javadoc", "java" ) );
        stereotypes.add( new DefaultArtifactType( "java-source", "jar", "sources", "java", false, false ) );
        stereotypes.add( new DefaultArtifactType( "war", "war", "", "java", false, true ) );
        stereotypes.add( new DefaultArtifactType( "ear", "ear", "", "java", false, true ) );
        stereotypes.add( new DefaultArtifactType( "rar", "rar", "", "java", false, true ) );
        stereotypes.add( new DefaultArtifactType( "par", "par", "", "java", false, true ) );
        session.setArtifactTypeRegistry( stereotypes );

        session.setArtifactDescriptorPolicy( new SimpleArtifactDescriptorPolicy( true, true ) );

        Properties sysProps = (Properties) System.getProperties().clone();
        session.setSystemProperties( sysProps );
        session.setConfigProperties( sysProps );

        // set timeout smaller than 30 minutes by default
        session.setConfigProperty(ConfigurationProperties.REQUEST_TIMEOUT, TimeUnit.MINUTES.toMillis(5));

        return session;
    }


    private void addServerConfig( DefaultRepositorySystemSession session, Server server )
    {
        Map<String,String> headers = new HashMap<String, String>();
        Xpp3Dom configuration = (Xpp3Dom) server.getConfiguration();
        Xpp3Dom httpHeaders = configuration.getChild( "httpHeaders" );
        for (Xpp3Dom httpHeader : httpHeaders.getChildren( "httpHeader" )) {
            Xpp3Dom name = httpHeader.getChild( "name" );
            String headerName = name.getValue();
            Xpp3Dom value = httpHeader.getChild( "value" );
            String headerValue = value.getValue();
            headers.put( headerName, headerValue );
        }
        session.setConfigProperty( String.format("%s.%s", ConfigurationProperties.HTTP_HEADERS, server.getId()), headers );
    }

    private Authentication getAuthentication( org.apache.maven.settings.Proxy proxy ) {
        // user, pass
        if( proxy.getUsername() != null ) {
            return new AuthenticationBuilder().addUsername( proxy.getUsername() )
                .addPassword( proxy.getPassword() ).build();
        }
        return null;
    }

    private Authentication getAuthentication( String repoId ) {
        Server server = m_settings.getServer(repoId);
        if (server != null && server.getUsername() != null) {
            AuthenticationBuilder authBuilder = new AuthenticationBuilder();
            authBuilder.addUsername( server.getUsername() ).addPassword( server.getPassword() );
            return authBuilder.build();
        }
        return null;
    }

    /**
     * Collects the dependency tree for the given file by extracting its pom.xml file
     */
    public DependencyNode collectDependenciesForJar(File jarFile, Filter<Dependency> excludeDependencyFilter) throws RepositoryException, IOException {
        // lets find the pom file
        PomDetails pomDetails = findPomFile(jarFile);
        if (pomDetails == null || !pomDetails.isValid()) {
            throw new IllegalArgumentException("No pom.xml file could be found inside the jar file: " + jarFile);
        }
        return collectDependencies(pomDetails, excludeDependencyFilter);
    }

    @Override
    public File getLocalRepository() {
        return localRepository == null ? null : localRepository.getBasedir();
    }

    public DependencyNode collectDependencies(PomDetails pomDetails, Filter<Dependency> excludeDependencyFilter) throws IOException, RepositoryException {
        Model model = pomDetails.getModel();
        return collectDependenciesFromPom(pomDetails.getFile(), model, excludeDependencyFilter);
    }


    protected DependencyNode collectDependenciesFromPom(File rootPom, Model model, Filter<Dependency> excludeDependencyFilter) throws RepositoryException, IOException {
        Map<String, String> props = Collections.singletonMap(ArtifactProperties.LOCAL_PATH, rootPom.toString());

        // lets load the model so we can get the version which is required for the transformer...
        String groupId = model.getGroupId();
        String artifactId = model.getArtifactId();
        String pomVersion = model.getVersion();
        String packaging = "pom";
        if (groupId == null || artifactId == null || pomVersion == null) {
            throw new IllegalArgumentException("Pomegranate pom.xml has missing groupId:artifactId:version " + groupId + ":" + artifactId + ":" + pomVersion);
        }
        Artifact root = new DefaultArtifact(groupId, artifactId, null, packaging, pomVersion, props, rootPom);

        return collectDependencies(root, pomVersion, excludeDependencyFilter);
    }

    protected DependencyNode collectDependencies(Artifact root, String pomVersion, final Filter<Dependency> excludeDependencyFilter) throws RepositoryException, IOException {
        final DefaultRepositorySystemSession session = newSession();
        try {
            List<RemoteRepository> repos = selectRepositories();
            assignProxyAndMirrors(repos);

            ArtifactDescriptorResult artifactDescriptorResult = m_repoSystem.readArtifactDescriptor(session, new ArtifactDescriptorRequest(root, repos, null));
            repos.addAll(artifactDescriptorResult.getRepositories());

            Dependency rootDependency = new Dependency(root, null);

            List<Dependency> dependencies = artifactDescriptorResult.getDependencies();

            final DefaultDependencyNode rootNode = new DefaultDependencyNode(rootDependency);
            GenericVersionScheme versionScheme = new GenericVersionScheme();
            rootNode.setVersion(versionScheme.parseVersion(pomVersion));
            rootNode.setVersionConstraint(versionScheme.parseVersionConstraint(pomVersion));
            DependencyNode pomNode = rootNode;

            //final Filter<Dependency> shouldExclude = Filters.or(DependencyFilters.testScopeFilter, excludeDependencyFilter, new NewerVersionExistsFilter(rootNode));
            final Filter<Dependency> shouldExclude = Filters.or(Arrays.asList(DependencyFilters.testScopeFilter, excludeDependencyFilter));
            DependencySelector dependencySelector = new AndDependencySelector(
                    new ScopeDependencySelector("test"),
                    new ExclusionDependencySelector(),
                    new DependencySelector() {
                        @Override
                        public DependencySelector deriveChildSelector(DependencyCollectionContext context) {
                            return this;
                        }

                        @Override
                        public boolean selectDependency(Dependency dependency) {
                            try {
                                return !DependencyFilters.matches(dependency, shouldExclude);
                            } catch (Exception e) {
                                failedToMakeDependencyTree(dependency, e);
                                return false;
                            }
                        }
                    });
            session.setDependencySelector(dependencySelector);

            // TODO no idea why we have to iterate through the dependencies; why can't we just
            // work on the root dependency directly?
            if (true) {
                for (Dependency dependency : dependencies) {
                    DependencyNode node = resolveDependencies(session, repos, pomNode, dependency, shouldExclude);
                    if (node != null) {
                        pomNode.getChildren().add(node);
                    }
                }
            } else {
                DependencyNode node = resolveDependencies(session, repos, pomNode, rootDependency, shouldExclude);
                if (node != null) {
                    pomNode = node;
                }
            }

            // now lets transform the dependency tree to remove different versions for the same artifact
            final DependencyGraphTransformationContext tranformContext = new DependencyGraphTransformationContext() {
                Map<Object, Object> map = new HashMap<>();

                public RepositorySystemSession getSession() {
                    return session;
                }

                public Object get(Object key) {
                    return map.get(key);
                }

                public Object put(Object key, Object value) {
                    return map.put(key, value);
                }
            };

            DependencyGraphTransformer transformer = new ReplaceConflictingVersionResolver();
            pomNode = transformer.transformGraph(pomNode, tranformContext);

            transformer = new DuplicateTransformer();
            pomNode = transformer.transformGraph(pomNode, tranformContext);

            return pomNode;
        } finally {
            releaseSession(session);
        }
    }

    public PomDetails findPomFile(File jar) throws IOException {
        JarFile jarFile = new JarFile(jar);
        File file = null;
        Properties properties = null;
        Enumeration<JarEntry> entries = jarFile.entries();
        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            String name = entry.getName();
            if (name.matches("META-INF/maven/.*/.*/pom.xml")) {
                InputStream in = jarFile.getInputStream(entry);
                // lets create a temporary file
                file = File.createTempFile("fabric-pomegranate-", ".pom.xml");
                IOHelpers.writeTo(file, in);
            } else if (name.matches("META-INF/maven/.*/.*/pom.properties")) {
                InputStream in = jarFile.getInputStream(entry);
                properties = new Properties();
                properties.load(in);
            }
            if (file != null && properties != null) {
                break;
            }
        }
        return new PomDetails(file, properties);
    }

    protected DependencyNode resolveDependencies(RepositorySystemSession session, List<RemoteRepository> repos, DependencyNode pomNode, Dependency dependency, final Filter<Dependency> shouldExclude) throws FailedToResolveDependency {
        if (!DependencyFilters.matches(dependency, shouldExclude)) {
            CollectRequest cr = new CollectRequest(dependency, repos);
            //request.setRequestContext("runtime");
            try {
                DependencyNode node = m_repoSystem.collectDependencies(session, cr).getRoot();
                DependencyFilter filter = new DependencyFilter() {
                    public boolean accept(DependencyNode node, List<DependencyNode> parents) {
                        return !DependencyFilters.matches(node, shouldExclude);
                    }
                };
                DependencyRequest request = new DependencyRequest(cr, filter);
                m_repoSystem.resolveDependencies(session, request);
                return node;
            } catch (DependencyResolutionException | DependencyCollectionException e) {
                handleDependencyResolveFailure(pomNode, dependency, e);
            }
        }
        return null;
    }

    // TODO: make this configurable
    private boolean throwExceptionsOnResolveDependencyFailure;

    protected void handleDependencyResolveFailure(DependencyNode pomNode, Dependency dependency, Exception e) throws FailedToResolveDependency {
        FailedToResolveDependency exception = new FailedToResolveDependency(dependency, e);
        if (throwExceptionsOnResolveDependencyFailure) {
            throw exception;
        } else {
            LOGGER.warn(exception.getMessage(), e);

            // lets just add the current dependency without its full dependency tree
            DefaultDependencyNode node = new DefaultDependencyNode(dependency);
            pomNode.getChildren().add(node);
        }
    }

    protected void failedToMakeDependencyTree(Object dependency, Exception e) {
        LOGGER.warn("Failed to make Dependency for " + dependency + ". " + e, e);
    }

}
