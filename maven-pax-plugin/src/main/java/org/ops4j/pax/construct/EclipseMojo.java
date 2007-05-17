package org.ops4j.pax.construct;

/*
 * Copyright 2007 Stuart McCulloch
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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipException;

import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.metadata.ArtifactMetadataSource;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.ArtifactCollector;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.eclipse.EclipsePlugin;
import org.apache.maven.plugin.eclipse.EclipseSourceDir;
import org.apache.maven.plugin.eclipse.writers.EclipseClasspathWriter;
import org.apache.maven.plugin.eclipse.writers.EclipseProjectWriter;
import org.apache.maven.plugin.eclipse.writers.EclipseSettingsWriter;
import org.apache.maven.plugin.eclipse.writers.EclipseWriterConfig;
import org.apache.maven.plugin.ide.IdeDependency;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.IOUtil;
import org.codehaus.plexus.util.xml.PrettyPrintXMLWriter;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.codehaus.plexus.util.xml.Xpp3DomBuilder;
import org.codehaus.plexus.util.xml.Xpp3DomWriter;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

/**
 * Extend maven-eclipse-plugin to get better classpath generation.
 * 
 * @goal eclipse
 */
public final class EclipseMojo extends EclipsePlugin
{
    /**
     * @parameter expression="${project}"
     * @required
     * @readonly
     */
    protected MavenProject project;

    /**
     * @parameter expression="${project}"
     * @readonly
     */
    protected MavenProject executedProject;

    /**
     * @component role="org.apache.maven.artifact.factory.ArtifactFactory"
     * @required
     * @readonly
     */
    protected ArtifactFactory artifactFactory;

    /**
     * @component role="org.apache.maven.artifact.resolver.ArtifactResolver"
     * @required
     * @readonly
     */
    protected ArtifactResolver artifactResolver;

    /**
     * @component role="org.apache.maven.artifact.resolver.ArtifactCollector"
     * @required
     * @readonly
     */
    protected ArtifactCollector artifactCollector;

    /**
     * @component role="org.apache.maven.artifact.metadata.ArtifactMetadataSource" hint="maven"
     */
    protected ArtifactMetadataSource artifactMetadataSource;

    /**
     * @parameter expression="${project.remoteArtifactRepositories}"
     * @required
     * @readonly
     */
    protected List remoteArtifactRepositories;

    /**
     * @parameter expression="${localRepository}"
     * @required
     * @readonly
     */
    protected ArtifactRepository localRepository;

    /**
     * @parameter expression="${outputDirectory}" alias="outputDirectory"
     *            default-value="${project.build.outputDirectory}"
     * @required
     */
    protected File buildOutputDirectory;

    /**
     * @parameter expression="${downloadSources}" default-value="false"
     */
    protected boolean downloadSources;

    protected boolean isWrappedJarFile = false;
    protected boolean isImportedBundle = false;

    public boolean setup()
        throws MojoExecutionException
    {
        project = super.project;

        setWtpversion( "none" );
        setDownloadSources( downloadSources );

        isWrappedJarFile = project.getProperties().containsKey( "jar.artifactId" );
        isImportedBundle = project.getProperties().containsKey( "bundle.artifactId" );

        if ( isImportedBundle )
        {
            // This forces eclipse plugin to work on pom packaging
            setEclipseProjectDir( project.getFile().getParentFile() );
        }

        return super.setup();
    }

    protected void setupExtras()
        throws MojoExecutionException
    {
        setArtifactFactory( artifactFactory );
        setArtifactResolver( artifactResolver );
        setArtifactMetadataSource( artifactMetadataSource );
        super.artifactCollector = artifactCollector;

        if ( project.getPackaging().equals( "bundle" ) || isImportedBundle )
        {
            // Inject values into private flags
            setField( "isJavaProject", true );
            setField( "pde", true );
        }
    }

    private void setField( final String name, final boolean flag )
    {
        try
        {
            // Attempt to bypass normal private field protection
            Field f = EclipsePlugin.class.getDeclaredField( name );
            f.setAccessible( true );
            f.setBoolean( this, flag );
        }
        catch ( Exception e )
        {
            System.out.println( "Cannot set " + name + " to " + flag + " exception=" + e );
        }
    }

    protected static EclipseSourceDir[] rejectLinkedSources( EclipseSourceDir[] sources )
    {
        int nonLinkedCount = 0;
        for ( int i = 0; i < sources.length; i++ )
        {
            // Remove external resources as these result in bogus links
            if ( new File( sources[i].getPath() ).isAbsolute() == false )
            {
                sources[nonLinkedCount++] = sources[i];
            }
        }

        EclipseSourceDir[] nonLinkedSources = new EclipseSourceDir[nonLinkedCount];
        System.arraycopy( sources, 0, nonLinkedSources, 0, nonLinkedCount );

        return nonLinkedSources;
    }

    protected static IdeDependency[] rejectLinkedDependencies( IdeDependency[] deps )
    {
        int nonLinkedCount = 0;
        for ( int i = 0; i < deps.length; i++ )
        {
            // Remove external compile/runtime dependencies as these result in bogus links
            if ( deps[i].isProvided() || deps[i].isTestDependency() || deps[i].getFile().isAbsolute() == false )
            {
                deps[nonLinkedCount++] = deps[i];
            }
        }

        IdeDependency[] nonLinkedDeps = new IdeDependency[nonLinkedCount];
        System.arraycopy( deps, 0, nonLinkedDeps, 0, nonLinkedCount );

        return nonLinkedDeps;
    }

    protected Manifest extractManifest( final File projectFolder )
        throws FileNotFoundException,
        IOException
    {
        Manifest manifest = null;

        // Eclipse wants bundle manifests at the top of the project directory
        String manifestPath = "META-INF" + File.separator + "MANIFEST.MF";
        File manifestFile = new File( projectFolder, manifestPath );
        manifestFile.getParentFile().mkdirs();

        if ( isImportedBundle )
        {
            try
            {
                // Existing manifest, unpacked from imported bundle
                manifest = new Manifest( new FileInputStream( manifestFile ) );

                Attributes attributes = manifest.getMainAttributes();
                if ( attributes.getValue( "Bundle-SymbolicName" ) == null )
                {
                    // Eclipse mis-behaves if the bundle has no symbolic name :(
                    attributes.putValue( "Bundle-SymbolicName", project.getArtifactId() );
                }
            }
            catch ( FileNotFoundException e )
            {
                // fall back to default manifest
            }
        }
        else
        {
            try
            {
                // Manifest (generated by bnd) can simply be extracted from the new bundle
                JarFile bundle = new JarFile( project.getBuild().getDirectory() + File.separator
                    + project.getBuild().getFinalName() + ".jar" );

                manifest = bundle.getManifest();
            }
            catch ( ZipException e )
            {
                // fall back to default manifest
            }
        }

        if ( null == manifest )
        {
            manifest = new Manifest();

            final String symbolicName = (project.getGroupId() + '_' + project.getArtifactId()).replace( '-', '_' );

            Attributes attributes = manifest.getMainAttributes();
            attributes.putValue( "Manifest-Version", "1" );
            attributes.putValue( "Bundle-ManifestVersion", "2" );
            attributes.putValue( "Bundle-SymbolicName", symbolicName );
            attributes.putValue( "Bundle-Name", project.getName() );
            attributes.putValue( "Bundle-Version", project.getVersion().replace( '-', '.' ) );

            // some basic OSGi dependencies, to help people get their code compiling...
            attributes.putValue( "Import-Package", "org.osgi.framework,org.osgi.util.tracker,"
                + "org.osgi.service.log,org.osgi.service.http,org.osgi.service.useradmin" );
        }

        manifest.write( new FileOutputStream( manifestFile ) );

        return manifest;
    }

    protected void patchClassPath( final File projectFolder, final String bundleClassPath )
        throws FileNotFoundException,
        XmlPullParserException,
        IOException
    {
        String[] paths =
        {
            "." // default classpath
        };

        if ( bundleClassPath != null )
        {
            paths = bundleClassPath.split( "," );
        }

        if ( isWrappedJarFile || isImportedBundle || paths.length > 1 )
        {
            File classPathFile = new File( projectFolder, ".classpath" );

            Xpp3Dom classPathXML = Xpp3DomBuilder.build( new FileReader( classPathFile ) );

            Map eclipsePaths = new HashMap();  
            for ( int i = 0; i < paths.length; i++ )
            {
                if ( paths[i].equals( "." ) && !isWrappedJarFile && !isImportedBundle )
                {
                    continue;
                }
                // any non-existing Bundle-ClassPath entries should be ignored and not added to the Eclipse classpath
                File pathEntry = new File( projectFolder, paths[i] ); 
                if ( !pathEntry.exists() )
                {
                    continue;
                }
                // TODO shall we check if the entry is not twice in the list?
                // only create an eclipse path entry for the top directories from the Bundle-ClassPath
                String canonicalPath = pathEntry.getCanonicalPath();
                boolean addEntry = true;
                for ( Iterator iter = eclipsePaths.entrySet().iterator(); iter.hasNext(); ) {
					Map.Entry entry = (Map.Entry) iter.next();
					// if we already have a parent folder for the current iteration entry
					if ( canonicalPath.startsWith( ((String) entry.getKey()) ) )
					{
						addEntry = false;
						break;
					}
					// if we the current iteration entry is a parent for en already added folder
					if ( ((String) entry.getKey()).startsWith( canonicalPath ) )
					{
						iter.remove();
						break;
					}
				}
                if ( addEntry )
                {
                	eclipsePaths.put(canonicalPath, paths[i]);
                }
            }
            
            for (Iterator iter = eclipsePaths.values().iterator(); iter.hasNext();) {
                // Add non-default bundle classpath entry
                Xpp3Dom classPathDot = new Xpp3Dom( "classpathentry" );
                classPathDot.setAttribute( "exported", "true" );
                classPathDot.setAttribute( "kind", "lib" );
                classPathDot.setAttribute( "path", (String) iter.next() );
                classPathXML.addChild( classPathDot );				
			}

            FileWriter writer = new FileWriter( classPathFile );
            Xpp3DomWriter.write( new PrettyPrintXMLWriter( writer ), classPathXML );
            IOUtil.close( writer );
        }
    }

    private void unpackMetadata( final File projectFolder )
        throws IOException
    {
        if ( isImportedBundle )
        {
            // nothing to do...
        }
        else
        {
            // Metadata files can simply be extracted from the new bundle
            JarFile bundle = new JarFile( project.getBuild().getDirectory() + File.separator
                + project.getBuild().getFinalName() + ".jar" );

            for ( final JarEntry entry : Collections.list( bundle.entries() ) )
            {
                final String name = entry.getName();

                if ( name.startsWith( "META-INF" ) || name.startsWith( "OSGI-INF" ) )
                {
                    File extractedFile = new File( projectFolder, name );

                    if ( entry.isDirectory() )
                    {
                        extractedFile.mkdirs();
                    }
                    else
                    {
                        InputStream contents = bundle.getInputStream( entry );
                        FileWriter writer = new FileWriter( extractedFile );
                        IOUtil.copy( contents, writer );
                        IOUtil.close( writer );
                    }
                }
            }
        }
    }

    public void writeConfiguration( IdeDependency[] deps )
        throws MojoExecutionException
    {
        try
        {
            EclipseWriterConfig config = createEclipseWriterConfig( deps );

            if ( isWrappedJarFile || isImportedBundle )
            {
                // Fudge directories so project is in the build directory without requiring extra links
                config.setEclipseProjectDirectory( new File( project.getBuild().getOutputDirectory() ) );
                config.setBuildOutputDirectory( new File( config.getEclipseProjectDirectory(), "temp" ) );
                config.setProjectBaseDir( config.getEclipseProjectDirectory() );

                // No sources required to build these bundles
                config.setSourceDirs( new EclipseSourceDir[0] );
            }
            else
            {
                // Avoid links wherever possible, as they're a real pain
                config.setSourceDirs( rejectLinkedSources( config.getSourceDirs() ) );
                config.setDeps( rejectLinkedDependencies( config.getDeps() ) );
            }

            // make sure project folder exists
            config.getEclipseProjectDirectory().mkdirs();

            new EclipseSettingsWriter().init( getLog(), config ).write();
            new EclipseClasspathWriter().init( getLog(), config ).write();
            new EclipseProjectWriter().init( getLog(), config ).write();

            final File projectFolder = config.getEclipseProjectDirectory();

            // Handle embedded jarfiles, etc...
            Manifest manifest = extractManifest( projectFolder );
            String classPath = manifest.getMainAttributes().getValue( "Bundle-ClassPath" );
            patchClassPath( projectFolder, classPath );
            unpackMetadata( projectFolder );
        }
        catch ( Exception e )
        {
            getLog().error( e );

            throw new MojoExecutionException( "ERROR creating Eclipse files", e );
        }
    }
}
