package aarddict;

import java.net.URI;
import java.net.URISyntaxException;

import android.util.Log;

public class LookupWord {

	final static String TAG = LookupWord.class.getName(); 
	
	public String nameSpace;
	public String word;
	public String section;
		
	public LookupWord() {		
	}
	
	public LookupWord(String nameSpace, String word, String section) {
		this.nameSpace = nameSpace;
		this.word = word;
		this.section = section;
	}	
	
    static LookupWord splitWord(String word) {
        if (word == null || word.equals("") || word.equals("#")) {
            return new LookupWord();
        }
		try {
			return splitWordAsURI(word);
		} catch (URISyntaxException e) {
			Log.d(TAG, "Word is not proper URI: " + word);
			return splitWordSimple(word);
		}		                
    }

    static LookupWord splitWordAsURI(String word) throws URISyntaxException {
		URI uri = new URI(word);
		String nameSpace = uri.getScheme();
		String lookupWord = uri.getSchemeSpecificPart();
		String section = uri.getFragment();
		return new LookupWord(nameSpace, lookupWord, section);     	
    }
    
    static LookupWord splitWordSimple(String word) {    
        String[] parts = word.split("#", 2);
        String section = parts.length == 1 ? null : parts[1];
        String nsWord = (!isEmpty(parts[0]) || !isEmpty(section)) ? parts[0] : word;
        String[] nsParts = nsWord.split(":", 2);      
        String lookupWord = nsParts.length == 1 ? nsParts[0] : nsParts[1];
        String nameSpace = nsParts.length == 1 ? null : nsParts[0];
        return new LookupWord(nameSpace, lookupWord, section);			    	
    }
	
    @Override
    public String toString() {
    	return String.format("LookupWord: name space \"%s\", word \"%s\", section \"%s\"", nameSpace, word, section);    	
    }
    
    public boolean isEmpty() {
    	return isEmpty(word) && isEmpty(section); 
    }
    
    static boolean isEmpty(String s) {
        return s == null || s.equals("");
    }    
}
