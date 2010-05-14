package aarddict;

import static java.lang.String.format;


import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.codehaus.jackson.map.ObjectMapper;

import android.util.Log;

import com.ibm.icu.text.Collator;

public final class Dictionary extends AbstractList<Entry> {

	final static String TAG = Dictionary.class.getName();
	
    final static Charset UTF8 = Charset.forName("utf8");

    final static Locale  ROOT = new Locale("", "", "");

    @SuppressWarnings("unchecked")
    public static Comparator<Entry>[] comparators = new Comparator[] {
            new EntryComparator(Collator.QUATERNARY),
            new EntryStartComparator(Collator.QUATERNARY),
            new EntryComparator(Collator.TERTIARY),
            new EntryStartComparator(Collator.TERTIARY),
            new EntryComparator(Collator.SECONDARY),
            new EntryStartComparator(Collator.SECONDARY),
            new EntryComparator(Collator.PRIMARY),
            new EntryStartComparator(Collator.PRIMARY)};


    public Metadata  metadata;
    public Header    header;
    RandomAccessFile file;
    String           sha1sum;

	private File origFile;

	private String articleURLTemplate;
    
    static ObjectMapper mapper = new ObjectMapper();
    static {
    	mapper.getDeserializationConfig().set(org.codehaus.jackson.map.DeserializationConfig.Feature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }
    
    public Dictionary(File file, File cacheDir, Map<UUID, Metadata> knownMeta) throws IOException {
    	this.origFile = file;
        init(new RandomAccessFile(file, "r"), cacheDir, knownMeta);
    }
    
    private void init(RandomAccessFile file, File cacheDir, Map<UUID, Metadata> knownMeta) throws IOException {
        this.file = file;
        this.header = new Header(file);
        this.sha1sum = header.sha1sum;
        if (knownMeta.containsKey(header.uuid)) {
        	this.metadata = knownMeta.get(header.uuid);  
        } else {
            String uuidStr = header.uuid.toString();            
            File metadataCacheFile = new File(cacheDir, uuidStr);
            if (metadataCacheFile.exists()) {
            	try {
            		long t0 = System.currentTimeMillis();
            		this.metadata = mapper.readValue(metadataCacheFile, Metadata.class);
            		knownMeta.put(header.uuid, this.metadata);
            		Log.d(TAG, format("Loaded meta for %s from cache in %s", metadataCacheFile.getName(), (System.currentTimeMillis() - t0)));
            	}
	        	catch(Exception e) {
	        		Log.e(TAG, format("Failed to restore meta from cache file %s ", metadataCacheFile.getName()), e);
	        	}            	
            }
            if (this.metadata == null) {
            	long t0 = System.currentTimeMillis();
            	byte[] rawMeta = new byte[(int) header.metaLength];
            	file.read(rawMeta);
            	String metadataStr = decompress(rawMeta);
            	this.metadata = mapper.readValue(metadataStr, Metadata.class);
            	Log.d(TAG, format("Read meta for in %s", header.uuid, (System.currentTimeMillis() - t0)));
            	knownMeta.put(header.uuid, this.metadata);
            	try {
            		mapper.writeValue(metadataCacheFile, this.metadata);
            		Log.d(TAG, format("Wrote metadata to cache file %s", metadataCacheFile.getName()));
            	}
            	catch (IOException e) {
            		Log.e(TAG, format("Failed to write metadata to cache file %s", metadataCacheFile.getName()), e);
            	}            	
            }                    
        }
        initArticleURLTemplate();        
    }

    public String getId() {
        return sha1sum;
    }
    
    public UUID getDictionaryId() {
    	return header.uuid;
    }
    
    @Override
    public int hashCode() {
        return sha1sum.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (!super.equals(obj))
            return false;
        if (getClass() != obj.getClass())
            return false;
        Dictionary other = (Dictionary) obj;
        if (sha1sum == null) {
            if (other.sha1sum != null)
                return false;
        }
        else if (!sha1sum.equals(other.sha1sum))
            return false;
        return true;
    }

    public String toString() {
        return String.format("%s %s/%s(%s)", this.metadata.title, this.header.volume, 
                this.header.of, this.sha1sum);
    };
    
    IndexItem readIndexItem(long i) throws IOException {
        long pos = this.header.index1Offset + i * 8;
        this.file.seek(pos);
        IndexItem indexItem = new IndexItem();
        indexItem.keyPointer = this.file.readUnsignedInt();
        indexItem.articlePointer = this.file.readUnsignedInt();
        return indexItem;
    }

    String readKey(long pointer) throws IOException {
        long pos = this.header.index2Offset + pointer;
        this.file.seek(pos);
        int keyLength = this.file.readUnsignedShort();
        return this.file.readUTF8(keyLength);
    }

    Article readArticle(long pointer) throws IOException {
        long pos = this.header.articleOffset + pointer;
        this.file.seek(pos);
        long articleLength = this.file.readUnsignedInt();

        byte[] articleBytes = new byte[(int) articleLength];
        this.file.read(articleBytes);
        String serializedArticle = decompress(articleBytes);
        Article a = Article.fromJsonStr(serializedArticle);
        a.dictionaryUUID = this.header.uuid;
        a.volumeId = this.header.sha1sum;
        a.pointer = pointer;
        return a;
    }

    static Iterator<Entry> EMPTY_ITERATOR = new ArrayList<Entry>().iterator();
    
    Iterator<Entry> lookup(final String word, final Comparator<Entry> comparator) {
    	Log.d(TAG, "Lookup " + word);
    	LookupWord parts = LookupWord.splitWord(word);
        if (parts.isEmpty()) {
            return EMPTY_ITERATOR;
        }
        
        final String lookupWord = parts.word;
        final String section = parts.section;
        final Entry lookupEntry = new Entry(this.getId(), lookupWord);
        final int initialIndex = binarySearch(this, lookupEntry, comparator);
        Iterator<Entry> iterator = new Iterator<Entry>() {

            int   index = initialIndex;
            Entry nextEntry;

            {
                prepareNext();
            }

            private void prepareNext() {
                Entry matchedEntry = get(index);
                nextEntry = (0 == comparator.compare(matchedEntry, lookupEntry)) ? matchedEntry : null;
                index++;
            }

            @Override
            public boolean hasNext() {
                return nextEntry != null && index < header.indexCount - 1;
            }

            @Override
            public Entry next() {
                Entry current = nextEntry;
                current.section = section;
                prepareNext();
                return current;
            }

            @Override
            public void remove() {
                throw new UnsupportedOperationException();
            }
        };

        return iterator;
    }
    
    public String getArticleURL(String title) {
    	String template = getArticleURLTemplate();
    	if (template != null) {
    		return template.replace("$1", title);
    	}
    	return null;
    }
    
    String getArticleURLTemplate() {
    	return articleURLTemplate;
    }
        
    private void initArticleURLTemplate() {
    	String[] serverAndArticlePath = getServerAndArticlePath();
    	String server = serverAndArticlePath[0];
    	String articlePath = serverAndArticlePath[1];
    	if (server != null && articlePath != null) {
    		articleURLTemplate = server + articlePath;
    	}
    	else {
    		Log.d(TAG, "Not enough metadata to generate article url template");
    	}
    }
    
    @SuppressWarnings("unchecked")
    private String[] getServerAndArticlePath() {
    	String[] result = new String[]{null, null};
    	if (metadata.siteinfo != null){
        	Map <String, Object> general = (Map <String, Object>)this.metadata.siteinfo.get("general");
        	if (general != null) {
        		Object server = general.get("server");
        		Object articlePath = general.get("articlepath");
        		if (server != null)
        			result[0] = server.toString();
        		if (articlePath != null)
        			result[1] = articlePath.toString();
        	}
    	}    	
    	return result;
    }
    
    Map <Integer, Entry> entryCache = new WeakHashMap<Integer, Entry>(100);
    
    @Override
    public Entry get(int index) {
    	if (entryCache.containsKey(index)) {
    		return entryCache.get(index);
    	}
        try {
            IndexItem indexItem = readIndexItem(index);
            String title = readKey(indexItem.keyPointer);
            Entry entry = new Entry(this.getId(), title, indexItem.articlePointer);
            entryCache.put(index, entry);
            return entry;
        }
        catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public int size() {
        return (int) header.indexCount;
    }

    public void close() throws IOException {
        file.close();
    };

    static String utf8(byte[] signature) {
        try {
            return new String(signature, "UTF-8");
        }
        catch (UnsupportedEncodingException e) {
            e.printStackTrace();
            return "";
        }
    }

    static String decompress(byte[] bytes) {
    	String type = null;
    	long t0 = System.currentTimeMillis();
        try {        	
            String result = decompressZlib(bytes);
            type = "zlib";
            return result;
        }
        catch (Exception e1) {
            try {
                String result = decompressBz2(bytes);
                type = "bz2";
                return result;
            }
            catch (IOException e2) {
                String result = utf8(bytes);
                type = "uncompressed";
                return result;
            }
        }
    	finally {
    		Log.d(TAG, "Decompressed " + type + " in " + (System.currentTimeMillis() - t0));
    	}
    }

    static String decompressZlib(byte[] bytes) throws IOException, DataFormatException {
        Inflater decompressor = new Inflater();
        decompressor.setInput(bytes);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            byte[] buf = new byte[1024];
            while (!decompressor.finished()) {
                int count = decompressor.inflate(buf);
                out.write(buf, 0, count);
            }
        }
        finally {
            out.close();
        }
        return utf8(out.toByteArray());
    }

    static String decompressBz2(byte[] bytes) throws IOException {
        BZip2CompressorInputStream in = new BZip2CompressorInputStream(new ByteArrayInputStream(bytes));
    	
        int n = 0;
        ByteArrayOutputStream out = new ByteArrayOutputStream(bytes.length*5);
        byte[] buf = new byte[1024];
        try {
            while (-1 != (n = in.read(buf))) {
                out.write(buf, 0, n);
            }
        }
        finally {
            in.close();
            out.close();
        }
        return utf8(out.toByteArray());
    }

    static UUID uuid(byte[] data) {
        long msb = 0;
        long lsb = 0;
        assert data.length == 16;
        for (int i = 0; i < 8; i++)
            msb = (msb << 8) | (data[i] & 0xff);
        for (int i = 8; i < 16; i++)
            lsb = (lsb << 8) | (data[i] & 0xff);
        return new UUID(msb, lsb);
    }

    static <T> int binarySearch(List<? extends T> l, T key, Comparator<? super T> c) {
        int lo = 0;
        int hi = l.size();
        while (lo < hi) {
            int mid = (lo + hi) / 2;
            T midVal = l.get(mid);
            int cmp = c.compare(midVal, key);
            if (cmp < 0) {
                lo = mid + 1;
            }
            else {
                hi = mid;
            }
        }
        return lo;
    }
    
    public CharSequence getDisplayTitle() {
    	return getDisplayTitle(true);
    }
    
    public CharSequence getDisplayTitle(boolean withVolumeNumber) {
        StringBuilder s = new StringBuilder(this.metadata.title);
        if (this.metadata.lang != null) {
            s.append(String.format(" (%s)", this.metadata.lang));
        }
        else {
            if (this.metadata.sitelang != null) {
                s.append(String.format(" (%s)", this.metadata.sitelang));
            }
            else {
                if (this.metadata.index_language != null && this.metadata.article_language != null) {
                    s.append(String.format(" (%s-%s)", this.metadata.index_language, this.metadata.article_language));
                }        	
            }            
        }
        if (this.header.of > 1 && withVolumeNumber) 
               s.append(String.format(" Vol. %s", this.header.volume));        
        return s.toString();
    }
    
    public void verify(VerifyProgressListener listener) throws IOException, NoSuchAlgorithmException {    	
    	FileInputStream fis = new FileInputStream(origFile);
    	fis.skip(44);
    	byte[] buff = new byte[1 << 16];    	
    	MessageDigest m = MessageDigest.getInstance("SHA-1");
    	int readCount;
    	long totalReadCount = 0;    	
    	double totalBytes = origFile.length() - 44;
    	boolean proceed = true;
    	while ((readCount=fis.read(buff)) != -1) {
    		m.update(buff, 0, readCount);
    		totalReadCount += readCount;
    		proceed = listener.updateProgress(this, totalReadCount/totalBytes);    		
    	}    	
    	fis.close();
    	if (proceed) {
    		BigInteger b = new BigInteger(1, m.digest());
    		String calculated = b.toString(16);
    		Log.d(TAG, "calculated: " + calculated + " actual: " + sha1sum);
    		listener.verified(this, calculated.equals(this.sha1sum));
    	}
    }    
}
