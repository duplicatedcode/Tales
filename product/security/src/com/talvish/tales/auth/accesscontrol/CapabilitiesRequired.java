// ***************************************************************************
// *  Copyright 2015 Joseph Molnar
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
package com.talvish.tales.auth.accesscontrol;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The capabilities of a particular capability family 
 * required by the method.
 * @author jmolnar
 *
 */
@Retention( RetentionPolicy.RUNTIME)
@Repeatable( CapabilitiesRequiredSet.class )
@Target( ElementType.METHOD )
public @interface CapabilitiesRequired {
	/**
	 * The claim that holds capabilities for a particular capability family. 
	 * This is not the same as the family. To get the capability family
	 * the associated TokenManager must be consulted.
	 * @return the claim name the capabilities are found within
	 */
	String claim( );
	
	/**
	 * The names of the capabilities required.
	 * @return the names of the capabilities required.
	 */
	String[] capabilities( );
}
