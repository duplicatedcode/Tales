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
package com.tales.client.http;

import com.tales.contracts.data.DataContract;
import com.tales.contracts.data.DataMember;

/**
 * The details about an exception that occurred during a service request.
 * @author jmolnar
 *
 */
@DataContract( name="com.tales.response.exception")
public class ResponseExceptionDetails {
	@DataMember( name="type" )private String type;
	@DataMember( name="message" )private String message;
	@DataMember( name="stack_trace" )private String stackTrace;

	/**
	 * The type of the exception.
	 * @return the type of the exception
	 */
	public String getType( ) {
		return type;
	}
	
	/**
	 * The message given in the exception.
	 * @return the message in the exception.
	 */
	public String getMessage( ) {
		return message;
	}
	
	/**
	 * The associated stack trace, if available.
	 * @return the associated stack trace
	 */
	public String getStackTrace( ) {
		return stackTrace;
	}
}