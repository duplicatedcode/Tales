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
package com.talvish.tales.parts.translators;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

public class StringToLocalDateTimeTranslator extends StringToObjectTranslatorBase implements Translator {

	public StringToLocalDateTimeTranslator( ) {
		this( true, null, null );
	}
	public StringToLocalDateTimeTranslator( boolean shouldTrim, Object theEmptyValue, Object theNullValue) {
		super(shouldTrim, theEmptyValue, theNullValue);
	}

	@Override
	public Object translate(Object anObject) {
		Object returnValue;
		if( anObject == null ) {
			returnValue = this.nullValue;
		} else {
			try {
				String stringValue = ( String )anObject;
				
				if( this.trim ) {
					stringValue = stringValue.trim();
				}
				if( stringValue.equals("") ) {
					returnValue = this.emptyValue;
				} else {
					returnValue = LocalDateTime.parse( stringValue, DateTimeFormatter.ISO_DATE_TIME );
				}
			} catch( DateTimeParseException e ) {
				throw new TranslationException( String.format( "Unable to translate '%s' into a datetime.", anObject ), e );
			} catch( ClassCastException e ) {
				throw new TranslationException( e );
			}
		}
		return returnValue;	
	}
}
