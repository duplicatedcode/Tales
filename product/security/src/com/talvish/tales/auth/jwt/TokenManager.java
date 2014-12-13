// ***************************************************************************
// *  Copyright 2014 Joseph Molnar
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
package com.talvish.tales.auth.jwt;

import java.lang.reflect.Type;
import java.nio.charset.Charset;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.Base64.Decoder;
import java.util.Base64.Encoder;
import java.util.Map.Entry;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import com.talvish.tales.contracts.data.DataContractTypeSource;
import com.talvish.tales.parts.reflection.JavaType;
import com.talvish.tales.parts.translators.TranslationException;
import com.talvish.tales.serialization.json.JsonTranslationFacility;
import com.talvish.tales.serialization.json.JsonTypeReference;
import com.talvish.tales.serialization.json.translators.ArrayToJsonArrayTranslator;
import com.talvish.tales.serialization.json.translators.JsonArrayToArrayTranslator;

/**
 * The manager is essentially a factory for creating json web tokens, but
 * allows registering json serialization handlers for particular claims.
 * The manager is able to handle string, number and boolean json values
 * automatically, but other types (e.g. arrays, and objects) are not 
 * handled UNLESS you use the registration mechanism. 
 * <p>
 * The general approach for the manager (and token) is that exceptions
 * are thrown when the data and format is unexpected. Things like
 * being signature failures or being expired do not throw exceptions,
 * instead there are methods for checking validity.
 * @author jmolnar
 *
 */
public class TokenManager {
	// members that are used to ultimately encode/decode the overall results
	private final static Gson gson = new GsonBuilder( ).serializeNulls( ).create();
	private final static JsonParser jsonParser = new JsonParser( );
	private final static Charset utf8 = Charset.forName( "UTF-8" );
	private final static Encoder base64Encoder = Base64.getUrlEncoder().withoutPadding();
	private final static Decoder base64Decoder = Base64.getUrlDecoder();
	
	// the following regular expression is based on RFC 3986 (Appendix B) but modified to require the
	// scheme and colon since the jwt spec calls for StringOrUri to be a URI not a URI-reference
	// note: this has left the matching groups in and appendix B could be used to pull out the pieces
	private final static String uriRegex = "^(([^:/?#]+):)(//([^/?#]*))?([^?#]*)(\\?([^#]*))?(#(.*))?$";
	private final static Pattern uriPattern = Pattern.compile( uriRegex );
	
	
	private final GenerationConfiguration defaultConfiguration;
	private final JsonTranslationFacility translationFacility;
	private final Map<String,JsonTypeReference> claimHandlers = new HashMap<>( );

	
	/**
	 * Creates a manager with a default configuration of a) no timing/expiration
	 * and b) uses HS256 for the signing.
	 */
	public TokenManager( ) {
		this( null, null );
	}
	
	/**
	 * Creates a manager using the specified default configuration and translation facility.
	 * The default configuration is used when generating a token from
	 * a set of headers and claims and specific configuration is not
	 * provided at that time.
	 * @param theDefaultConfiguration the default configuration to use 
	 * @param theTranslationFacility the facility to aid the translation of types to/from json
	 */
	public TokenManager( GenerationConfiguration theDefaultConfiguration, JsonTranslationFacility theTranslationFacility ) {
		// TODO: consider other configuration for things like, how to handle the unknown json objects that come down the pipe
		//		 could make it so it leaves it as a string to deal, but we want that configurable
		
		if( theDefaultConfiguration == null ) {
			defaultConfiguration = new GenerationConfiguration( null, SigningAlgorithm.HS256 );
		} else {
			defaultConfiguration = theDefaultConfiguration;
		}
		if( theTranslationFacility == null ) {
			translationFacility = new JsonTranslationFacility( new DataContractTypeSource( ) );
		} else {
			translationFacility = theTranslationFacility;
		}

		// going to register handlers for specific claims
		
		// we have a bit of special handling for the string[] to handle the single items to/from the json array
		JavaType elementType = new JavaType( String.class );
		JsonTypeReference elementTypeReference = translationFacility.getTypeReference( elementType );

		_registerClaimType( 
				"aud", 
				new JsonTypeReference( 
	        			new JavaType( String[].class ), 
	        			"list[string]",
	        			new JsonArrayToArrayTranslator( elementType.getUnderlyingClass(), elementTypeReference.getFromJsonTranslator(), true ),
	        			new ArrayToJsonArrayTranslator( elementTypeReference.getToJsonTranslator( ), true ) ) );
		// could consider doing the other's like exp or nbf
	}

	/**
	 * Registers a type for a particular claim (or header). This means that when this particular 
	 * claim comes up it will use the translators associated with that type to convert to and from the json.
	 * @param theClaimName the name of the claim to associate with a type
	 * @param theType the type of the claim, which means the system will attempt to translate to and from that type
	 */
	public void registerClaimType( String theClaimName, Type theType) {
		Preconditions.checkArgument( !Strings.isNullOrEmpty( theClaimName ), "need a claim name" );
		Preconditions.checkNotNull( theType, "need type for claim '%s'", theClaimName );
		Preconditions.checkArgument( !claimHandlers.containsKey( theClaimName ), "a type/handler was already registered for claim '%s'", theClaimName );

		_registerClaimType(theClaimName, theType );
	}

	/**
	 * Private registration mechanism used by the public version and the constructor. 
	 * @param theClaimName the name of the claim to associate with a type
	 * @param theType the type of the claim, which means the system will attempt to translate to and from that type
	 */
	private final void _registerClaimType( String theClaimName, Type theType ) {
		// we register the handler and not type since it speeds up the runtime slightly 
		// and does give us more options for if we want more custom handling of claims
		claimHandlers.put( 
				theClaimName, 
				translationFacility.getTypeReference( new JavaType( theType ) ) ); 
	}
	
	/**
	 * Registers a type for a particular claim (or header). This means that when this particular 
	 * claim comes up it will use the translators associated with that type to convert to and from the json.
	 * @param theClaimName the name of the claim to associate with a type
	 * @param theTypeReference the type reference of the claim, which means the system will attempt to translate to and from 
	 */
	public void registerClaimType( String theClaimName, JsonTypeReference theTypeReference ) {
		Preconditions.checkArgument( !Strings.isNullOrEmpty( theClaimName ), "need a claim name" );
		Preconditions.checkNotNull( theTypeReference, "need type reference for claim '%s'", theClaimName );
		Preconditions.checkArgument( !claimHandlers.containsKey( theClaimName ), "a type/handler was already registered for claim '%s'", theClaimName );

		_registerClaimType( theClaimName, theTypeReference );
	}

	/**
	 * Private registration mechanism used by the public version and the constructor. 
	 * @param theClaimName the name of the claim to associate with a type
	 * @param theTypeReference the type reference of the claim, which means the system will attempt to translate to and from 
	 */
	private final void _registerClaimType( String theClaimName, JsonTypeReference theTypeReference ) {
		// we register the handler and not type since it speeds up the runtime slightly 
		// and does give us more options for if we want more custom handling of claims
		claimHandlers.put( 
				theClaimName, 
				theTypeReference ); 
	}

	/**
	 * Creates a json web token from a set of claims and a secret, if signing. This call
	 * uses the default configuration.
	 * <p>
	 * This call is made when a new, never having existed, token is to be created and sent out into the world.
	 * @param theClaims the claims to be placed into the token
	 * @param theSecret the secret to use when signing the token, it can be null if signing is not enabled
	 * @return returns a json web token 
	 */
	public JsonWebToken generateToken( Map<String,Object> theClaims, String theSecret ) {
		return this.generateToken( null, theClaims, theSecret, defaultConfiguration );
	}

	/**
	 * Creates a json web token from a set of claims and a secret and a set of configuration.
	 * <p>
	 * This call is made when a new, never having existed, token is to be created and sent out into the world.
	 * @param theClaims the claims to be placed into the token
	 * @param theSecret the secret to use when signing the token, it can be null if signing is not enabled
	 * @param theConfiguration the configuration to use when creating the tken
	 * @return returns a json web token 
	 */
	public JsonWebToken generateToken( Map<String,Object> theClaims, String theSecret, GenerationConfiguration theConfiguration ) {
		return this.generateToken( null, theClaims, theSecret, theConfiguration );
	}

	/**
	 * Creates a json web token from a set of claims and a secret and a set of configuration. In addition
	 * it allows you to specify additional headers. This really meant to support the JWT spec where it
	 * indicates that encrypted tokens can have claims in the header, since the header would be in the 
	 * clear. Encryption, however, is not yet supported.
	 * <p>
	 * This call is made when a new, never having existed, token is to be created and sent out into the world.
	 * @param theHeaders the headers to used for the token
	 * @param theClaims the claims to be placed into the token
	 * @param theSecret the secret to use when signing the token, it can be null if signing is not enabled
	 * @param theConfiguration the configuration to use when creating the tken
	 * @return returns a json web token 
	 */
	public JsonWebToken generateToken( Map<String,Object> theHeaders, Map<String,Object> theClaims, String theSecret, GenerationConfiguration theConfiguration ) {
		// make sure we have defaults if not provided
		if( theConfiguration == null ){
			theConfiguration = defaultConfiguration;
		}
		if( theHeaders == null ) {
			theHeaders = new HashMap<>( );
		} else {
			theHeaders = new HashMap<>( theHeaders ); // copying for no side-effects
		}
		if( theClaims == null ) {
			theClaims = new HashMap<>( );
		} else {
			theClaims = new HashMap<>( theClaims ); // copying for no side-effects
		}
		
		SigningAlgorithm signingAlgorithm = theConfiguration.getSigningAlgorithm( );	
		
		// need to setup the configuration based headers

		// first we have the signing algorithm
		if( signingAlgorithm != null ) {
			Preconditions.checkArgument( !Strings.isNullOrEmpty( theSecret ), "signing of type '%s' is configured but the secret is missing", signingAlgorithm.name( ) );
			theHeaders.put( "alg", signingAlgorithm.name( ) );
		} else {
			theHeaders.put( "alg", "none" );
		}
		// not putting in the following because it is only needed when doing encryption (and value would be 'JWE')
		// theHeaders.put( "typ",  "JWT" );
		// we now process the map and produce the header segment 
		String headersSegment = processMap( theHeaders );

		
		// need to setup the configuration based claims
		// if the configuration is not null/false then 
		// this code over writes the values in the claims
		// but if null/false then the developer using 
		// this method can use their own values by 
		// setting the values in the map parameters
		
		// the indication of who issues the token
		if( theConfiguration.getIssuer( ) != null ) {
			theClaims.put( "iss",  theConfiguration.getIssuer( ) );
		}
		// unique id (if configured)
		if( theConfiguration.shouldGenerateId() ) {
			theClaims.put( "jti", UUID.randomUUID().toString( ) );
		}
		// some timing based configuration
		long now = System.currentTimeMillis( ) / 1000l;
		if( theConfiguration.shouldIncludeIssuedTime( ) ) {
			theClaims.put( "iat", now );
		}
		Long validDelay = theConfiguration.getValidDelayDuration( );
		if( validDelay != null ) {
			theClaims.put( "nbf",  now + validDelay );
		} else {
			validDelay = 0l; // we set this for the expiration below
		}
		Long expiresIn = theConfiguration.getValidDuration( );
		if( expiresIn != null ) {
			theClaims.put( "exp", now + validDelay + expiresIn );
		}
		// we generate the json from the passed-in/configured claims 
		String claimsSegment = processMap( theClaims );		
		
		// we create the combined segments that will be signed
		String combinedSegments = String.join( ".", headersSegment, claimsSegment );
		
		
		// just need to create that final segments, the signature
		if( signingAlgorithm != null ) {
			// and now we need sign (using the configuration algorithm)
			Mac mac;
			
			try {
				mac = Mac.getInstance( signingAlgorithm.getJavaName( ) );
				mac.init( new SecretKeySpec( theSecret.getBytes( utf8 ), signingAlgorithm.getJavaName( ) ) );
	
				byte[] signatureBytes = mac.doFinal( combinedSegments.getBytes( ) );		
				String signatureSegment = base64Encoder.encodeToString( signatureBytes );
				combinedSegments = String.join( ".", combinedSegments, signatureSegment );				
	
			} catch( NoSuchAlgorithmException e ) {
				throw new IllegalArgumentException( String.format( "Could not find the algorithm to used for the token." ), e );
			} catch( InvalidKeyException e ) {
				throw new IllegalStateException( String.format( "Key issues attempting to generate token." ), e );
			}
		} else {
			// no signing, so slap a dot on the end
			combinedSegments += ".";
		}
		// and now we have our token
		return new JsonWebToken( theHeaders, theClaims, combinedSegments, true );
	}
	
	/**
	 * Helper method that takes the map of claims/headers and then converts them
	 * into utf-8, json, base64 encoded string. This will used any registered
	 * claims handlers to create the correct json string.
	 * @param theMap the map of claims
	 * @return the utf-8, json, based64 encoded string of the claims
	 */
	private String processMap( Map<String,Object> theMap  ) {
		JsonTypeReference claimHandler;
		JsonObject outputJson = new JsonObject( );

		for( Entry<String,Object> entry : theMap.entrySet() ) {
			// quick note, the JWT spec says headers and claims shouldn't have
			// duplicates but apps allow it so there is no attempt to enforce
			claimHandler = this.claimHandlers.get( entry.getKey( ) );
			if( claimHandler != null ) {
				try {
					outputJson.add( entry.getKey( ), ( JsonElement )claimHandler.getToJsonTranslator( ).translate( entry.getValue( ) ) );
				} catch( TranslationException e ) {
					// this will help with understanding problems ...
					throw new IllegalArgumentException( String.format( "Claim '%s' is using a custom translation that failed to translate the associated value.", entry.getKey( ) ), e );
				}
			} else if( entry.getValue( ) instanceof String ) {
				outputJson.addProperty( entry.getKey( ), validateString( entry.getKey( ), ( String )entry.getValue( ) ) );
			} else if( entry.getValue( ) instanceof Number ) {
				outputJson.addProperty( entry.getKey( ), ( Number )entry.getValue( ) );
			} else if( entry.getValue( ) != null && Boolean.class.isAssignableFrom( entry.getValue().getClass( ) ) ) {
				// no need to check for boolean primitive type since the map cannot hold onto primitive types, booleans will be boxed into Boolean
				outputJson.addProperty( entry.getKey( ), ( Boolean )entry.getValue( ) );
			} else {
				throw new IllegalArgumentException( String.format( "Claim '%s' is using type '%s', which has no mechanism for translation.", entry.getKey(), entry.getValue( ).getClass().getSimpleName() ) );
			}
		}
		return base64Encoder.encodeToString( gson.toJson( outputJson ).getBytes( utf8 ) );
	}
	
	/**
	 * Helper method that ensures that the string passed in is 
	 * of the correct format. According to the JWT spec any 
	 * string with a colon must be a URI. 
	 * @param theName the name of the claim
	 * @param theValue the value of the claim
	 * @throws IllegalArgumentException thrown if the value is null or if the value contains a colon but doesn't confirm to the URI spec
	 * @return return the string the string that was passed in
	 */
	private String validateString( String theName, String theValue ) {
		if( theValue == null ) {
			throw new IllegalArgumentException( String.format( "Claim '%s' was set with a null value, which is not permitted.", theName ) );
		} else if( theValue.indexOf( ':' ) < 0 ) {
			return theValue;
		} else if( uriPattern.matcher( theValue ).matches( ) ) {
			return theValue;
		} else {
			throw new IllegalArgumentException( String.format( "Claim '%s' is using a value '%s', which contains a ':' but does not match the URI spec '%s'.", theName, theValue, uriRegex ) );
		}
	}
	
	/**
	 * Creates a json web token from a string and an optional secret, which is needed if the token is signed.
	 * <p>
	 * This call is made when an existing token has been received and the claims are to be used and the
	 * token needs validation.
	 * @param theTokenString the string representation of the token to generate into the full tken
	 * @param theSecret the secret to use to verify the signature of the token, it can be null if signing is not enabled
	 * @return returns a json web token 
	 */
	public JsonWebToken generateToken( String theTokenString, String theSecret ) {
		Preconditions.checkArgument( !Strings.isNullOrEmpty( theTokenString ), "need a token string to generate a token" );

		String[] segments = theTokenString.split( "\\." );
		Preconditions.checkArgument( segments.length >= 2, "token contains wrong number of segments" ); 

		// need to process the items in the header and claims
		Map<String,Object> claimItems = processSegment( segments[ 1 ] );
		Map<String,Object> headerItems = processSegment( segments[ 0 ] );
		
		// now we verify the signature
		Object signgingAlgorithmObject = headerItems.get( "alg" );
		Preconditions.checkNotNull( signgingAlgorithmObject, "the token is missing the signing algorithm" );
		String signingAlgorithmString = signgingAlgorithmObject.toString( );
		// it is possible that the fromString call below returns null which means 
		// 'alg' was 'none' and there wasn't any signing (and that is okay), and 
		// the fromString will ensure it is a valid value
		SigningAlgorithm signingAlgorithm = SigningAlgorithm.fromString( signingAlgorithmString );
		// now that we know the signing algorithm we can verify the segment count (which will change when we do encryption)
		Preconditions.checkArgument( ( signingAlgorithm == null && segments.length == 2 ) || ( signingAlgorithm != null && segments.length == 3 ), "token contains wrong number of segments" ); 
		
		// and finally we check the signatures (assuming it was signed)
		boolean verified;
		if( signingAlgorithm != null ) {
			Preconditions.checkArgument( !Strings.isNullOrEmpty( theSecret ), "signing of type '%s' was indicated but the secret is missing", signingAlgorithm.name( ) );
			verified = verifySignature( 
					String.join( ".", segments[ 0 ], segments[ 1 ] ), // we combine these since the signature requires it
					segments[ 2 ], 
					theSecret, 
					signingAlgorithm );
		} else {
			verified = true;
		}
		
		return new JsonWebToken( headerItems, claimItems, theTokenString, verified );
	}

	/**
	 * Helper method that takes a string segment (e.g. headers, claims) and 
	 * base64 decodes, parses out the json and generates a map of the values. 
	 * @param theSegment the segment to process
	 * @return the map of values generated from the segment
	 */
	private Map<String,Object> processSegment( String theSegment ) {
		Map<String,Object> outputItems = new HashMap<>( );
		JsonTypeReference claimHandler;

		JsonObject inputJson = ( JsonObject )jsonParser.parse( new String( base64Decoder.decode( theSegment ), utf8 ) );
		for( Entry<String,JsonElement> entry : inputJson.entrySet( ) ) {
			claimHandler = this.claimHandlers.get( entry.getKey( ) );
			if( claimHandler != null ) {
				outputItems.put( entry.getKey(), claimHandler.getFromJsonTranslator().translate( entry.getValue() ) );
			} else if( entry.getValue( ).isJsonPrimitive( ) ) {
				JsonPrimitive primitiveJson = ( JsonPrimitive )entry.getValue( );
				if( primitiveJson.isString( ) ) {
					outputItems.put( entry.getKey(), primitiveJson.getAsString( ) );
				} else if( primitiveJson.isNumber( ) ) {
					outputItems.put( entry.getKey(), primitiveJson.getAsNumber( ) );
				} else if( primitiveJson.isBoolean( ) ) {
					outputItems.put( entry.getKey(), primitiveJson.getAsBoolean( ) );
				} else {
					throw new IllegalArgumentException( String.format( "Claim '%s' is a primitive json type with value '%s', which has no mechanism for translation.", entry.getKey(), primitiveJson.getAsString() ) );	
				}
			} else {
				throw new IllegalArgumentException( String.format( "Claim '%s' is a primitive json type with value '%s', which has no mechanism for translation.", entry.getKey(), entry.getValue( ).getAsString() ) );
			}
		}
		return outputItems;
	}
	
	/**
	 * Helper method that verifies the signature that was part of the of string token.
	 * @param theCombinedString the data string portions to process for re-signing
	 * @param theSignature the signature to check against
	 * @param theSecret the secret to use to reconstruct the signature
	 * @param theSigningAlgorithm the signing algorithm to use to reconstruct the signature
	 * @return true means the signature was verified and match, false means it didn't
	 */
	private boolean verifySignature( String theCombinedString, String theSignature, String theSecret, SigningAlgorithm theSigningAlgorithm ) {
		Mac mac;
		
		try {
			mac = Mac.getInstance( theSigningAlgorithm.getJavaName( ) );
			mac.init( new SecretKeySpec( theSecret.getBytes( utf8 ), theSigningAlgorithm.getJavaName( ) ) );

			return Arrays.equals( 
					mac.doFinal( theCombinedString.getBytes( ) ), 
					base64Decoder.decode( theSignature ) );

		} catch( NoSuchAlgorithmException e ) {
			throw new IllegalArgumentException( String.format( "Could not find the algorithm to used for the token." ), e );
		} catch( InvalidKeyException e ) {
			throw new IllegalStateException( String.format( "Key issues attempting to generate token." ), e );
		}
	}
}