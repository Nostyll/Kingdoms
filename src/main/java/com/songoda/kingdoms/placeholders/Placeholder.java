package com.songoda.kingdoms.placeholders;

import java.util.Arrays;

import com.google.common.reflect.TypeToken;
import com.songoda.kingdoms.Kingdoms;

public abstract class Placeholder<T> {
	
	protected String[] syntaxes;
	
	public Placeholder(String... syntaxes) {
		this.syntaxes = syntaxes;
	}
	
	public String[] getSyntaxes() {
		return syntaxes;
	}
	
	@SuppressWarnings("serial")
	public Class<? super T> getType() {
		return new TypeToken<T>(getClass()){}.getRawType();
	}
	
	/**
	 * Replace a placeholder from the given object.
	 * 
	 * @param object The object to get the placeholder replacement from.
	 * @return The final replaced placeholder.
	 */
	public abstract Object replace(T object);
	
	@SuppressWarnings("unchecked")
	public String replace_i(Object object) {
		Object replacement = null;
		try {
			replacement = replace((T) object);
			if (replacement == null)
				return null;
		} catch (ClassCastException e) {
			Kingdoms.consoleMessage("There was an issue with class casting being incorrect in the placeholders: " + Arrays.toString(syntaxes));
			return null;
		}
		return replacement.toString();
	}
	
}
