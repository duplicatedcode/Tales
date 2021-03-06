// ***************************************************************************
// *  Copyright 2011 Joseph Molnar
// *
// *  Licensed under the Apache License, Version 2.0 (the "License");
// *  you may not use this file except in compliance with the License.
// *  You may obtain a copy of the License at
// *
// *      http://www.apache.org/licenses/LICENSE-2.0
// *
// *  Unless required by applicable law or agreed to in writing, software
// *  distributed under the License is distributed on an "AS IS" BASIS,
// *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// *  See the License for the specific language governing permissions and
// *  limitations under the License.
// ***************************************************************************
package com.talvish.tales.services.http;

import java.security.KeyStore;
import java.security.KeyStoreException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import javax.servlet.DispatcherType;
import javax.servlet.Filter;
import javax.servlet.Servlet;

import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.talvish.tales.communication.HttpEndpoint;
import com.talvish.tales.contracts.services.http.HttpContract;
import com.talvish.tales.contracts.services.http.HttpServletContract;
import com.talvish.tales.serialization.Readability;
import com.talvish.tales.services.ConfigurationConstants;
import com.talvish.tales.services.InterfaceBase;
import com.talvish.tales.services.Service;
import com.talvish.tales.services.OperationContext.Details;
import com.talvish.tales.services.http.servlets.DefaultServlet;
import com.talvish.tales.services.http.servlets.EnableHeaderOverridesFilter;
import com.talvish.tales.system.ExecutionLifecycleException;
import com.talvish.tales.system.ExecutionLifecycleState;
import com.talvish.tales.system.configuration.ConfigurationException;
import com.talvish.tales.system.status.MonitorableStatusValue;
import com.talvish.tales.system.status.RatedLong;

/**
 * This class represents a host/port that servlets can be bound to. 
 * @author jmolnar
 *
 */
public abstract class HttpInterfaceBase extends InterfaceBase {
	/**
	 * Stored information regarding the status of the interface.
	 * @author jmolnar
	 *
	 */
	public class Status {
		private AtomicLong badUrls				= new AtomicLong( 0 );
		private RatedLong badUrlRate			= new RatedLong( );
		
		/**
		 * Default empty constructor.
		 */
		public Status( ) {
		}

		/**
		 * Returns that a bad URL has occurred.
		 */
		public void recordBadUrl( ) {
			badUrls.incrementAndGet();
			badUrlRate.increment();
		}

		/**
		 * Returns the number of bad url requests on the interface.
		 * @return the number of bad url requests
		 */
		@MonitorableStatusValue( name = "bad_urls", description = "The total number of times the interface has processed bad url requests since the interface was started." )
		public long getBadUrls( ) {
			return this.badUrls.get();
		}
		
		/**
		 * Returns the rate of the number of bad url requests on the interface.
		 * @return the rate of the number of bad url requests
		 */
		@MonitorableStatusValue( name = "bad_url_rate", description = "The rate, in seconds, of the number of bad urls processed as measured over 10 seconds." )
		public double getBadUrlRate( ) {
			return this.badUrlRate.calculateRate();
		}
	}
	
	private static final Logger logger = LoggerFactory.getLogger( HttpInterfaceBase.class );

	private final SslContextFactory sslFactory;
	
	private final Collection<HttpEndpoint> endpoints; 
	private final HttpServletServer server;
	private final ServletContextHandler servletContext;

	private final Status status = new Status( );
	
	// TODO: add a constructor that takes the parameters manually instead of loaded from the configuration
	
	/**
	 * Constructor taking the items needed for the interface to start.
	 * @param theName the name given to the interface
	 * @param theService the service the interface will be bound to
	 */
	public HttpInterfaceBase( String theName, Service theService ) {
		super( theName, theService );

		server = new HttpServletServer( this );
		
		// so we need to see if we have SSL settings for this interface
		String sslKeyStoreConfigName = String.format( ConfigurationConstants.HTTP_INTERFACE_SSL_KEY_STORE, theName );
		String sslCertAliasConfigName = String.format( ConfigurationConstants.HTTP_INTERFACE_SSL_CERT_ALIAS, theName );
		// if we have a key store defined on the  service and if so create the ssl factory
		if( getService( ).getConfigurationManager().contains( sslKeyStoreConfigName ) ) {
			String keyStoreName = getService( ).getConfigurationManager().getStringValue( sslKeyStoreConfigName ) ;
			String certAlias = getService( ).getConfigurationManager().getStringValue( sslCertAliasConfigName, "" );
			try {
				KeyStore keyStore = getService( ).getKeyStoreManager().getKeyStore( keyStoreName );
				
				if( keyStore == null ) {
					throw new ConfigurationException( String.format( "Interface '%s' is attempting to use a non-existent key store called '%s'.", theName, keyStoreName ) );
				} else {
					sslFactory = new SslContextFactory();
					sslFactory.setKeyStore( keyStore );
					// if we have the cert alias available, then we use

					
					if( !Strings.isNullOrEmpty( certAlias ) ) {
						if( !keyStore.containsAlias( certAlias ) ) {
							throw new ConfigurationException( String.format( "Interface '%s' is attempting to use a non-existent certificate alias '%s' on key store '%s'.", theName, certAlias, keyStoreName ) );
						} else {
							sslFactory.setCertAlias( certAlias );
						}
					}
					// oddly we need to grab the key again, even though the store is open
					// I'm not very happy with having to do this, but Jetty needs the password again
					sslFactory.setKeyStorePassword( getService( ).getConfigurationManager().getStringValue( String.format( ConfigurationConstants.SECURITY_KEY_STORE_PASSWORD_FORMAT, keyStoreName ) ) );
				}
			} catch( KeyStoreException e ) {
				throw new IllegalStateException( String.format( "Interface '%s' is using an invalid key store called '%s'.", theName, keyStoreName ) );
			}
		} else {
			sslFactory = null;
		}

		ConnectorConfiguration connectorConfiguration = null;

    	// load up the the connector configuration
    	String connectorName = String.format( ConfigurationConstants.HTTP_INTERFACE_CONNECTOR, this.getName( ) );
		if( getService( ).getConfigurationManager().contains( connectorName ) ) {
	    	ConnectorConfigurationManager connectorConfigurationManager = this.getService( ).getFacility( ConnectorConfigurationManager.class );
	    	String connectorConfigurationName = getService( ).getConfigurationManager().getStringValue( connectorName );
		    connectorConfiguration = connectorConfigurationManager.getConfiguration( connectorConfigurationName );
	    	if( connectorConfiguration == null ) {
	    		throw new IllegalArgumentException( String.format( "Could not find connector configuration '%s' for interface '%s'.", connectorConfigurationName, this.getName( ) ) );
	    	} else {
	    		logger.info( "Interface '{}' is using connector configuration '{}'.", this.getName( ), connectorConfigurationName );
	    	}
		} else {
			connectorConfiguration = new ConnectorConfiguration( );
    		logger.info( "Interface '{}' is using default connector configuration.", this.getName( ) );
		}

		// load up the endpoints from the configuration and set them on the service
		List<String> endPoints = getService( ).getConfigurationManager( ).getListValue( String.format( ConfigurationConstants.HTTP_INTERFACE_ENDPOINTS, theName), String.class );
		Preconditions.checkState( endPoints != null && endPoints.size() > 0, String.format( "HttpInterface '%s' does not have any endpoints defined.",  theName ) );

		int count = 0;
		HttpEndpoint endpoint;
		ArrayList<HttpEndpoint> modifiableEndpoints = new ArrayList<HttpEndpoint>( endPoints.size() );
		
		for( String stringEndpoint : endPoints ) {
			endpoint = new HttpEndpoint( stringEndpoint, false );
			if( endpoint.getScheme().equals( "https") ) {
				if( sslFactory == null ) {
					throw new IllegalArgumentException( String.format( "The http interface '%s' is attempting to use SSL on endpoint '%s', but SSL is not configured for this interface.", this.getName( ), endpoint.toString( ) ) );
				}
				addSecureConnector( String.format( "%s%03d", theName, count ), endpoint, connectorConfiguration  );
			} else {
				addNonSecureConnector( String.format( "%s%03d", theName, count ), endpoint, connectorConfiguration  );
			}
			modifiableEndpoints.add( endpoint );
			count += 1;
		}
		endpoints = Collections.unmodifiableCollection( modifiableEndpoints );

		// we need to setup the overall context
		servletContext = new ServletContextHandler( server, "/", false, false ); // set the context on the root; no sessions, security

		// now we set the max form content size based on the connector definition
		if( connectorConfiguration != null && connectorConfiguration.getMaxFormContentSize() != null ) {
			servletContext.setMaxFormContentSize( connectorConfiguration.getMaxFormContentSize( ) );
			server.setAttribute("org.eclipse.jetty.server.Request.maxFormContentSize", connectorConfiguration.getMaxFormContentSize());
			logger.info( "Interface '{}' is set to use the max form content size of '{}'.", this.getName( ), servletContext.getMaxFormContentSize() );
			
		} else {
    		logger.info( "Interface '{}' is set to use the default max form content size of '{}'.", this.getName( ), servletContext.getMaxFormContentSize( ) );
		}
		
		// save these for servlets to access
		servletContext.setAttribute( AttributeConstants.INTERFACE_SERVLET_CONTEXT, this );
		servletContext.setAttribute( AttributeConstants.SERVICE_SERVLET_CONTEXT, getService( ) );

		// get the status blocks setup
		getStatusManager().register( "interface", status );
	}
	
	/**
	 * Returns the endpoints exposed by the interface.
	 * @return the endpoints exposed by the interface
	 */
	public final Collection<HttpEndpoint> getEndpoints( ) {
		return this.endpoints;
	}
	
	/**
	 * Returns the servlet context backing this interface.
	 * @return the servlet context
	 */
	protected final ServletContextHandler getServletContext( ) {
		return this.servletContext;
	}
	
	/**
	 * Returns the underlying Jetty server managing the servlets
	 * @return the underlying Jetty server
	 */
	protected final HttpServletServer getServer( ) {
		return this.server;
	}

	/**
	 * Method that can be called externally to indicate a bad url 
	 * was attempted on the interface.
	 */
	public final void recordBadUrl( ) {
		this.status.recordBadUrl();
	}
	
	/**
	 * Sets the default level used for showing details in responses.
	 * @param theDetails the new default level
	 */
	public void setDefaultResponseDetails( Details theDetails ) {
		this.server.setDefaultResponseDetails( theDetails );
	}

	/**
	 * Sets the default target for readability in responses.
	 * @param theReadability the new default target
	 */
	public void setDefaultResponseReadability( Readability theReadability ) {
		this.server.setDefaultResponseReadability( theReadability );
	}

	/**
	 * Starts the interface.
	 * @throws Exception
	 */
	@Override
	protected void onStart( ) {
		logger.info( "Interface '{}' starting on {}.", this.getName( ), endpointListHelper( ) );
		try {
			// we see if we have a servlet covering the 'defaults' (items not explicitly mapped out)
			ServletMapping mapping = this.getServletContext().getServletHandler().getServletMapping( "/" ); 
			if( mapping == null ) {
				// if we dont' have a default handler, we set one up to handle the 404
				Servlet defaultServlet = new DefaultServlet();
		        HttpContract defaultContract = new HttpServletContract( this.getName( ) + "_default", "Default, error throwing, servlet.", new String[] { "20130201" }, defaultServlet, "/" );
		    	this.getContractManager( ).register( defaultContract );
				ContractServletHolder defaultHolder = new LaxContractServletHolder( defaultContract, defaultServlet, this );
				this.getServletContext().addServlet( defaultHolder, "/" );
				logger.info( "Default servlet handling for interface '{}' under context '{}' is handled by the default Tales servlet.", this.getName(), this.servletContext.getContextPath( ) );
			} else {
				logger.info( "Default servlet handling for interface '{}' under context '{}' is handled by '{}'.", this.getName(), this.servletContext.getContextPath( ), mapping.getServletName() );
			}
			// now see if we have header overrides enable
			boolean enableHeaderOverrides = getService( ).getConfigurationManager().getBooleanValue( String.format( ConfigurationConstants.HTTP_INTERFACE_ENABLE_HEADER_OVERRIDES, this.getName( ) ), false ) ;
			if( enableHeaderOverrides ) {
				logger.warn( "Interface '{}' has default header overrides enabled, allowing clients to override headers using query string parameters. This should only be used for debugging purposes.", this.getName( ) );
				this.bind( new EnableHeaderOverridesFilter( ), "/" );
			} else {
				logger.warn( "Interface '{}' has default header overrides disabled.", this.getName( ) );
			}
			// now we setup the default response readability
			String defaultResponseReadabilityString = getService( ).getConfigurationManager().getStringValue( String.format( ConfigurationConstants.HTTP_INTERFACE_DEFAULT_RESPONSE_READABILITY, this.getName( ) ), "MACHINE" ) ;
			Readability defaultResponseReadability = Readability.HUMAN;
			try {
				defaultResponseReadability = Readability.valueOf( Readability.class, defaultResponseReadabilityString );
			} catch( IllegalArgumentException e ) {
				// absorbing since it doesn't really matter, but we log
				logger.warn( "Interface '{}' unable to set the default response responsibility since the value '{}' is not valid.", this.getName( ), defaultResponseReadabilityString );
			}
			logger.info( "Interface '{}' has default response readability set to '{}'.", this.getName( ), defaultResponseReadability );
			this.setDefaultResponseReadability( defaultResponseReadability );
			server.start();
		} catch( Exception e ) {
			throw new ExecutionLifecycleException( "Unable to start the underlying server.", e );
		}
	}
	
	/**
	 * Stops the interface.
	 * @throws Exception
	 */
	@Override
	protected void onStop( ) {
		logger.info( "Interface '{}' stopping on {}.", this.getName( ), endpointListHelper( ) );
		try {
			server.stop( );
			server.join( ); // wait for it to stop
		} catch( Exception e ) {
			throw new ExecutionLifecycleException( "Unable to stop or join the underlying server.", e );
		}
	}
	
	/**
	 * Simple helper method to list out the endpoints.
	 * @return a string listing the endpoints.
	 */
	private String endpointListHelper( ) {
		StringBuilder listBuilder = new StringBuilder();
		boolean wroteOne = false;
		
		for( HttpEndpoint endpoint : endpoints ) {
			if( wroteOne ) {
				listBuilder.append( ", " );
			}
			listBuilder.append( "'" );
			listBuilder.append( endpoint.toString() );
			listBuilder.append( "'" );
			wroteOne = true;
		}
		return listBuilder.toString( );
	}
	
	/**
	 * Binds a filter into the interface along the specified path.
	 * @param theFilter the filter being bound
	 * @param theRoot the path the filter is being bound to
	 */
	public void bind( Filter theFilter, String theRoot ) {
    	Preconditions.checkState( this.getState() == ExecutionLifecycleState.CREATED || this.getState() == ExecutionLifecycleState.STARTING, "Cannot bind a filter to interface '%s' while it is in the '%s' state.", this.getName(), this.getState( ) );
    	Preconditions.checkNotNull( theFilter, "must provide a filter" );
    	Preconditions.checkArgument( !Strings.isNullOrEmpty( theRoot ), "need a path to bind to" );
    	Preconditions.checkArgument( theRoot.startsWith( "/" ), "the path '%s' must be a reference from the root (i.e. start with '/')", theRoot );

    	logger.info( "Binding filter '{}' on interface '{}' to http path '{}'.", theFilter.getClass().getSimpleName(), this.getName(), theRoot );

    	String path = theRoot; 
    	if( path.endsWith( "/") ) {
    		path = path + "*";
    	} else if( !path.endsWith( "*" ) ) {
    		path = path + "/*";
    	} 

    	// and properly bind the filter to the context
    	servletContext.addFilter( new FilterHolder( theFilter ), path, EnumSet.allOf( DispatcherType.class ) );
	}

	/**
	 * This method is called to setup the non-secure connectors needed.
	 * @param theConnectorName the name to give the connector
	 * @param theEndpoint the endpoint to bind to
	 */
    private void addNonSecureConnector( String theConnectorName, HttpEndpoint theEndpoint, ConnectorConfiguration theConfiguration ) {
    	// here is how to get setup
    	// http://git.eclipse.org/c/jetty/org.eclipse.jetty.project.git/tree/examples/embedded/src/main/java/org/eclipse/jetty/embedded/ManyConnectors.java

    	// let's setup our jetty http configuration
    	HttpConfiguration httpConfiguration = generateJettyHttpConfiguration( theConfiguration );
    	
    	// now we create our connector
    	ServerConnector connector = new ServerConnector( 
    			this.server, 
    			null, // use the server's executor
    			null, // use the server's scheduler
    			null, // use a default bye pool with default configuration
    			theConfiguration.getAcceptors() == null ? -1 : theConfiguration.getAcceptors( ), 
    			theConfiguration.getSelectors() == null ? -1 : theConfiguration.getSelectors( ),  
    			new HttpConnectionFactory( httpConfiguration ) );
    	
    	if( theConfiguration.getAcceptQueueSize() != null ) {
    		connector.setAcceptQueueSize( theConfiguration.getAcceptQueueSize( ) );
    	}
    	if( theConfiguration.getIdleTimeout( ) != null ) {
    		connector.setIdleTimeout( theConfiguration.getIdleTimeout( ) );
    	}

    	// if we have a host, set it so we bind to a particular interface
    	if( !theEndpoint.getHost( ).equals( "*" ) ) {
    		connector.setHost( theEndpoint.getHost( ) );
    	}
    	// now setup the port and name
    	connector.setPort( theEndpoint.getPort() );
    	connector.setName( theConnectorName );

    	// now we add the connector to the server
    	server.addConnector( connector );

    	// display our configuration for the connector
		displayConnectorConfiguration( connector, theEndpoint, httpConfiguration, theConfiguration );
    }

	/**
	 * This method is called to setup the secure connectors needed.
	 * @param theConnectorName the name to give the connector
	 * @param theEndpoint the end point to bind to
	 */
    private void addSecureConnector( String theConnectorName, HttpEndpoint theEndpoint, ConnectorConfiguration theConfiguration ) {
    	// here is how to get setup
    	// http://wiki.eclipse.org/Jetty/Howto/Configure_SSL (older version)
    	// http://git.eclipse.org/c/jetty/org.eclipse.jetty.project.git/tree/examples/embedded/src/main/java/org/eclipse/jetty/embedded/ManyConnectors.java

    	// let's setup our jetty http configuration
    	HttpConfiguration httpConfiguration = generateJettyHttpConfiguration( theConfiguration );
    	
    	// still need to setup the default security items
    	httpConfiguration.setSecureScheme( "https");
    	httpConfiguration.setSecurePort( theEndpoint.getPort( ) );
    	httpConfiguration.addCustomizer( new SecureRequestCustomizer( ) );

    	// now we create our connector
    	ServerConnector connector = new ServerConnector( 
    			this.server, 
    			null, // use the server's executor
    			null, // use the server's scheduler
    			null, // use a default byte pool with default configuration
    			theConfiguration.getAcceptors() == null ? -1 : theConfiguration.getAcceptors( ), 
    			theConfiguration.getSelectors() == null ? -1 : theConfiguration.getSelectors( ),  
    			new SslConnectionFactory( this.sslFactory,  "http/1.1" ),
    			new HttpConnectionFactory( httpConfiguration ) );
    	
    	if( theConfiguration.getAcceptQueueSize() != null ) {
    		connector.setAcceptQueueSize( theConfiguration.getAcceptQueueSize( ) );
    	}
    	if( theConfiguration.getIdleTimeout( ) != null ) {
    		connector.setIdleTimeout( theConfiguration.getIdleTimeout( ) );
    	}

    	// if we have a host, set it so we bind to a particular interface
    	if( !theEndpoint.getHost( ).equals( "*" ) ) {
    		connector.setHost( theEndpoint.getHost( ) );
    	}
    	// now setup the port and name
    	connector.setPort( theEndpoint.getPort() );
    	connector.setName( theConnectorName );

    	// now we add the connector to the server
    	server.addConnector( connector );

    	// display our configuration for the connector
		displayConnectorConfiguration( connector, theEndpoint, httpConfiguration, theConfiguration );
    }
    
    private HttpConfiguration generateJettyHttpConfiguration( ConnectorConfiguration theConfiguration ) {
    	HttpConfiguration httpConfiguration = new HttpConfiguration();

    	if( theConfiguration.getHeaderCacheSize( ) != null ) {
    		httpConfiguration.setHeaderCacheSize( theConfiguration.getHeaderCacheSize( ) );
    	}
    	if( theConfiguration.getRequestHeaderSize( ) != null ) {
    		httpConfiguration.setRequestHeaderSize( theConfiguration.getRequestHeaderSize( ) );
    	}
    	if( theConfiguration.getResponseHeaderSize( ) != null ) {
    		httpConfiguration.setResponseHeaderSize( theConfiguration.getResponseHeaderSize( ) );
    	}
    	if( theConfiguration.getOutputBufferSize( ) != null ) {
    		httpConfiguration.setOutputBufferSize( theConfiguration.getOutputBufferSize( ) );
    	}
    	httpConfiguration.setSendDateHeader( false ); // TODO: verify if I should manually do this, and in what way this sets it (UTC, local, etc)
    	httpConfiguration.setSendServerVersion( false );
    	httpConfiguration.setSendXPoweredBy( false );

    	return httpConfiguration;
    }
    

    /**
     * Helper method that sets the connector configuration options on the specific connector.
     * @param theConnector the connector to setup
     * @param theEndpoint the endpoint that was configured
     * @param theConfigurationName the name of the set of configuration values to use to setup
     */
    private void displayConnectorConfiguration( ServerConnector theConnector, HttpEndpoint theEndpoint, HttpConfiguration theHttpConfiguration, ConnectorConfiguration theConfiguration ) {
    	Preconditions.checkNotNull( theConnector, "need a jetty connector to apply settings to" );
    	Preconditions.checkNotNull( theHttpConfiguration, "need jetty http configuration if you are going to apply it" );
    	Preconditions.checkNotNull( theConfiguration, "need configuration if you are going to apply it" );
    	
    	StringBuilder settingBuilder = new StringBuilder();

    	if( theConfiguration.getAcceptors() != null ) {
    		settingBuilder.append( "\n\tAcceptors: " );
    	} else {
    		settingBuilder.append( "\n\tAcceptors (default): " );
    	}
		settingBuilder.append( theConnector.getAcceptors( ) );

		if( theConfiguration.getAcceptQueueSize() != null ) {
    		settingBuilder.append( "\n\tAccept Queue Size: " );
    	} else {
    		settingBuilder.append( "\n\tAccept Queue Size (default): " );
    	}
		settingBuilder.append( theConnector.getAcceptQueueSize( ) );

    	if( theConfiguration.getSelectors() != null ) {
    		settingBuilder.append( "\n\tSelectors: " );
    	} else {
    		settingBuilder.append( "\n\tSelectors (default): " );
    	}
		settingBuilder.append( theConnector.getSelectorManager().getSelectorCount() );

    	if( theConfiguration.getIdleTimeout() != null ) {
    		settingBuilder.append( "\n\tIdle Time: " );
    	} else {
    		settingBuilder.append( "\n\tIdle Time (default): " );
    	}
		settingBuilder.append( theConnector.getIdleTimeout( ) );


    	if( theConfiguration.getHeaderCacheSize() != null ) {
    		settingBuilder.append( "\n\tHeader Cache Size: " );
    	} else {
    		settingBuilder.append( "\n\tHeader Cache Size (default): " );
    	}
		settingBuilder.append( theHttpConfiguration.getHeaderCacheSize( ) );


		if( theConfiguration.getRequestHeaderSize() != null ) {
    		settingBuilder.append( "\n\tRequest Header Size: " );
    	} else {
    		settingBuilder.append( "\n\tRequest Header Size (default): " );
    	}
		settingBuilder.append( theHttpConfiguration.getRequestHeaderSize( ) );

    	if( theConfiguration.getResponseHeaderSize() != null ) {
    		settingBuilder.append( "\n\tResponse Header Size: " );
    	} else {
    		settingBuilder.append( "\n\tResponse Header Size (default): " );
    	}
		settingBuilder.append( theHttpConfiguration.getResponseHeaderSize( ) );

    	if( theConfiguration.getOutputBufferSize() != null ) {
    		settingBuilder.append( "\n\tOutput Buffer Size: " );
    	} else {
    		settingBuilder.append( "\n\tOutput Buffer Size (default): " );
    	}
		settingBuilder.append( theHttpConfiguration.getOutputBufferSize( ) );


		settingBuilder.append( "\n\tReuse Address (default): " );
		settingBuilder.append( theConnector.getReuseAddress( ) );

		settingBuilder.append( "\n\tSocket Linger Time (default): " );
		settingBuilder.append( theConnector.getSoLingerTime( ) );

    	logger.info( "Interface '{}' on endpoint '{}' is using configuration: {}", this.getName(), theEndpoint.toString(), settingBuilder.toString() );
    }
}
