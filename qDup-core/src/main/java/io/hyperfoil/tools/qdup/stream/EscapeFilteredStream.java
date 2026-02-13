package io.hyperfoil.tools.qdup.stream;

import io.hyperfoil.tools.yaup.Sets;
import org.jline.jansi.AnsiColors;
import org.jline.jansi.AnsiMode;
import org.jline.jansi.AnsiType;
import org.jline.jansi.io.AnsiOutputStream;
import org.jline.jansi.io.AnsiProcessor;
import org.jboss.logging.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.invoke.MethodHandles;
import java.nio.charset.StandardCharsets;
import java.util.Set;

public class EscapeFilteredStream extends MultiStream {

    private final static Logger logger = Logger.getLogger(MethodHandles.lookup().lookupClass());
    private final AnsiOutputStream jansiStream;
    //creating buffer array
    private final ByteArrayOutputStream barrierBuffer = new ByteArrayOutputStream();

    private static final int CR = 13;
    private static final int ESC = 27;
    private static final int SHIFT_IN = 15;
    private static final int SHIFT_OUT = 14;
    private static final Set<Character> CONTROL_SUFFIX = Sets.of(
            'A','B','C','D','E','F','G','H','J','K','S','T','f','m','i','n','s','u','h','l',(char)7,(char)156
    );

    private byte[] buffered;
    private int writeIndex = 0;

    public EscapeFilteredStream() {
        this("");
    }

    public EscapeFilteredStream(String name) {
        super(name);
        this.buffered = new byte[20 * 1024];

        OutputStream optStream = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                //EscapeFilteredStream.this.superWrite(new byte[]{(byte) b}, 0, 1);
                barrierBuffer.write(b);
            }
            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                //EscapeFilteredStream.this.superWrite(b, off, len);
                barrierBuffer.write(b, off, len);
            }
        };

        // Processor
        // [NOTE] These empty overrides are still valid in JLine 3
        AnsiProcessor strippingProcessor = new AnsiProcessor(optStream) {
            @Override protected void processSetAttribute(int attribute) {}
            @Override protected void processSetForegroundColor(int color) {}
            @Override protected void processSetBackgroundColor(int color) {}
            @Override protected void processEraseScreen(int eraseOption) {}
            @Override protected void processEraseLine(int eraseOption) {}
        };


        this.jansiStream = new AnsiOutputStream(
                optStream,
                () -> 0,
                AnsiMode.Strip,
                strippingProcessor,
                //AnsiType.Native,
                AnsiType.Unsupported,
                AnsiColors.TrueColor,
                StandardCharsets.UTF_8,
                null,
                null,
                false
        );
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        byte[] filtered = new byte[len];
        int fIdx = 0;

        for (int i = 0; i < len; i++) {
            byte c = b[off + i];

            if (c == 0) {
                continue;
            }

            if (c == SHIFT_IN || c == SHIFT_OUT) {
                continue;
            }

            if (c == '#' && i + 1 < len && b[off + i + 1] == ESC) {
                continue;
            }

            if (c == ESC && i + 1 < len) {
                byte next = b[off + i + 1];
                if (next == '=' || next == '>') {
                    i++; // Skip ESC
                    continue;
                }
            }

            filtered[fIdx++] = c;
        }

        if (fIdx > 0) {
            //jansiStream.write(filtered, 0, fIdx);
            jansiStream.write(filtered, 0, fIdx);
            jansiStream.flush();


            if (barrierBuffer.size() > 0) {
                superWrite(barrierBuffer.toByteArray(), 0, barrierBuffer.size());
                barrierBuffer.reset(); // Clear buffer for next time
            }
        }
    }

    @Override
    public void write(byte[] b) throws IOException {
        write(b, 0, b.length);
    }

    @Override
    public void flush() throws IOException {
        jansiStream.flush();
    }

    @Override
    public void close() throws IOException {
        jansiStream.close();
    }

    protected void superWrite(byte[] b, int off, int len) throws IOException {
        if (len < 0 || b.length - off < len) {
            logger.error("superWrite invalid write");
        }
        super.write(b, off, len);
    }

    public void reset() { writeIndex = 0; }
    public String getBuffered() { return new String(buffered, 0, writeIndex); }


    public static int copyNonNulBytes(byte[] source, int sourceOffset, byte[] destination, int destinationOffset, int len) {
        int nonNullIndex = -1;
        int lastWriteIndex = destinationOffset;
        for (int i = sourceOffset; i < sourceOffset + len; i++) {
            if (source[i] == 0) {
                if (nonNullIndex > -1) {
                    int writeLength = i - nonNullIndex;
                    System.arraycopy(source, nonNullIndex, destination, lastWriteIndex, writeLength);
                    nonNullIndex = -1;
                    lastWriteIndex += writeLength;
                }
            } else {
                if (nonNullIndex == -1) nonNullIndex = i;
            }
        }
        if (nonNullIndex > -1) {
            int writeLength = len - (nonNullIndex - sourceOffset);
            System.arraycopy(source, nonNullIndex, destination, lastWriteIndex, writeLength);
            lastWriteIndex += writeLength;
        }
        return lastWriteIndex;
    }

    public boolean isCompleteEscapeSequence(byte[] b, int off, int len) {
        boolean rtrn =
                (len ==1 && (b[off] == CR || b[off] == SHIFT_IN || b[off] == SHIFT_OUT))
                        ||
                        (
                                len >= 2 && b[off]=='#' && b[off+1] == ESC && isCompleteEscapeSequence(b,off+1,len-1)
                        ) || (
                        len >= 2 && b[off]=='e' && b[off+1]==8    //miss-configured backspace in zsh
                ) ||
                        (
                                b[off]==ESC
                                        &&
                                        (
                                                (
                                                        ( len>=3 && b[off+1]=='[' && CONTROL_SUFFIX.contains((char)b[off+len-1]))
                                                                || (len == 2 && b[off+1]=='=')
                                                                || (len == 2 && b[off+1]=='>')
                                                ) || (
                                                        len>=15
                                                                && b[off+ 1]==']'
                                                                && b[off+ 2]=='7'
                                                                && b[off+ 3]=='7'
                                                                && b[off+ 4]=='7'
                                                                && b[off+ 5]==';'
                                                                && b[off+ 6]=='p'
                                                                && b[off+ 7]=='r'
                                                                && b[off+ 8]=='e'
                                                                && b[off+ 9]=='e'
                                                                && b[off+10]=='x'
                                                                && b[off+11]=='e'
                                                                && b[off+12]=='c'
                                                                && b[off+13]==ESC
                                                                && b[off+14]=='\\'
                                                )
                                        )
                        );
        if(!rtrn && len >=5 && b[off]==ESC && b[off+1]==']' && b[off+2]=='0' && b[off+3]==';'){
            int i=0;
            while(off + i < len && b[off + i] != (char)7){
                i++;
            }
            rtrn = off + i < len && b[off + i] == (char) 7;
        }
        return rtrn;
    }

    public int escapeLength(byte[] b, int off, int len) {
        boolean matching = true;
        int rtrn = 0;
        if (b[off] == 'e'){ // check for miss-configured zsh backspace
            if(len == 1 ){
                rtrn = 1;
            }else if(len >= 2 && b[off+1] == 8){
                rtrn = 2;
            }
        }else if( len >= 2 && b[off] == '#' && b[off+1] == ESC){ //zsh
            rtrn = 1+escapeLength(b,off+1,len-1);
        }else if( b[off]==ESC ) {//\003
            rtrn = 1;
            if (2 <= len) {
                if (b[off + 1] == '[') {
                    rtrn = 2;//the initial 2 matched characters
                    if (rtrn < len && b[off + rtrn] == '?') {
                        rtrn = 3;// ^[[? indicates the sequence is for private use
                    }
                    while (matching && rtrn < len) {
                        while (rtrn < len && b[off + rtrn] >= '0' && b[off + rtrn] <= '9') {//digit
                            rtrn++;
                        }
                        //not an integer, if ; continue
                        if (rtrn < len) {
                            if (b[off + rtrn] == ';') {
                                rtrn++;
                            } else if (CONTROL_SUFFIX.contains((char) b[off + rtrn])) {
                                rtrn++;//we matched this character too
                                matching = false;//end of match
                            } else {//false alarm, not a valid escape character
                                rtrn = 0;//why do we reset to 0? I don't remember...
                                matching = false;
                            }
                        } else {
                            matching = false;//stop the match at end of len
                        }
                    }
                } else if (b[off + 1] == ']') {
                    rtrn = 2;
                    int i=0;
                    //VTE preexec
                    byte tofind[] = new byte[]{'7','7','7',';','p','r','e','e','x','e','c',ESC,'\\'};
                    while(rtrn+i < len && i < tofind.length && matching ) {
                        matching = tofind[i]==b[off+rtrn+i];
                        if(matching) {
                            i++;
                        }else{
                            i=0;
                            rtrn=0;
                        }
                    }
                    rtrn += i;
                    if(i == 0){//did not match VTE
                        if(len > 2 && b[off + 2] == '0'){
                            rtrn = 2;
                        }
                        if(rtrn == 2 && len > 3 && b[off + 3] == ';'){
                            while(rtrn+i < len && b[off+rtrn+i] != (char)7){
                                i++;
                            }
                            rtrn = rtrn + i;
                            if(off+rtrn < len && b[off+rtrn] == (char)7){
                                rtrn++;
                            }
                        }
                    }

                } else if (b[off + 1] == '=') {
                    rtrn = 2; //^[= is from DEC VT100s application mode
                } else if (b[off + 1] == '>') {
                    rtrn = 2;
                } else {
                    rtrn = 0;
                }
            }
        }else if (b[off] == SHIFT_IN || b[off] == SHIFT_OUT){
            rtrn = 1;
        }/*else if ( b[off] == CR ){ //doing this breaks things
            rtrn = 1;
        }*/else{
            rtrn = 0;
        }
        return rtrn;
    }
}