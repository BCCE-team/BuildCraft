"""Real snapshot codec and inbox: large schemas, malformed frames and request limits."""
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[2]
JAVA=ROOT/'source-shared/src/main/java/buildcraft'
PROBE=r'''
import buildcraft.api.v2.config.*;
import buildcraft.api.v2.config.ConfigDefinition.*;
import buildcraft.lib.net.ConfigurationWire;
import buildcraft.lib.net.ConfigurationWire.*;
import java.util.*;
public final class Probe {
    static void eq(Object a,Object b){if(!Objects.equals(a,b))throw new AssertionError(a+" != "+b);}
    static void reject(Runnable r){try{r.run();throw new AssertionError("Accepted invalid data");}catch(IllegalArgumentException expected){}}
    static Entry entry(int index,boolean large) {
        String text=large?"\u4e16".repeat(4096):"text";
        var d=new ConfigDefinition("example:k"+index,Section.WORLD,List.of("Category"),Scope.SERVER,Kind.STRING,text,0,0,List.of(),
            "n","d","t","Name","Description",text,ApplyPolicy.WORLD_RESTART);
        return new Entry(d,text,text);
    }
    public static void main(String[] args) {
        var small=new Result(true,3,"",List.of(entry(0,false)));
        var inbox=new Inbox();var single=ConfigurationWire.frames(1,small);eq(single.size(),1);
        inbox.accept(single.get(0));eq(inbox.take(1),null);eq(inbox.received(1),0);
        inbox.begin(1);inbox.accept(single.get(0));eq(inbox.take(1),small);eq(inbox.take(1),null);
        // A single definition can exceed the legacy packet size: split bytes, not entries.
        var entries=new ArrayList<Entry>();for(int i=0;i<70;i++)entries.add(entry(i,true));
        var result=new Result(true,8,"",entries);var frames=ConfigurationWire.frames(2,result);
        if(frames.size()<100)throw new AssertionError("Expected multi-megabyte payload");
        inbox.begin(2);inbox.accept(single.get(0));eq(inbox.received(2),0);
        for(int i=0;i<frames.size();i++) {
            var frame=frames.get(i);if(frame.data().length>ConfigurationWire.FRAME_BYTES)throw new AssertionError();
            inbox.accept(frame);if(i<frames.size()-1)eq(inbox.take(2),null);
        }
        eq(inbox.take(2),result);
        // Missing, repeated or inconsistent frame metadata fails closed.
        inbox.begin(2);inbox.accept(frames.get(1));eq(inbox.take(2).allowed(),false);
        inbox.begin(2);inbox.accept(frames.get(0));inbox.accept(frames.get(0));eq(inbox.take(2).allowed(),false);
        inbox.begin(2);inbox.accept(frames.get(0));var second=frames.get(1);
        inbox.accept(new Frame(2,second.total()+1,second.offset(),second.data()));eq(inbox.take(2).allowed(),false);
        inbox.begin(2);inbox.accept(frames.get(0));inbox.cancel(2);for(var f:frames)inbox.accept(f);eq(inbox.take(2),null);
        inbox.begin(2);inbox.accept(frames.get(0));inbox.begin(1);inbox.accept(frames.get(1));inbox.accept(single.get(0));eq(inbox.take(1),small);
        reject(()->new Frame(1,ConfigurationWire.MAX_SNAPSHOT_BYTES+1,0,new byte[1]));
        reject(()->new Frame(1,10,1,new byte[9]));reject(()->new Frame(1,10,0,new byte[9]));
        var raw=single.get(0).data();byte[] trailing=Arrays.copyOf(raw,raw.length+1);
        inbox.begin(1);inbox.accept(new Frame(1,trailing.length,0,trailing));eq(inbox.take(1).allowed(),false);
        raw[0]=0;inbox.begin(1);inbox.accept(new Frame(1,raw.length,0,raw));eq(inbox.take(1).allowed(),false);
        reject(()->new Result(true,0,"",List.of(entry(0,false),entry(0,false))));
        var tooBig=new ArrayList<Entry>();for(int i=0;i<200;i++)tooBig.add(entry(i,true));
        reject(()->ConfigurationWire.frames(1,new Result(true,0,"",tooBig)));
        eq(ConfigurationWire.requestBytes(Map.of()),32L);
        if(ConfigurationWire.requestBytes(Map.of("example:k","\u4e16".repeat(4096)))>=ConfigurationWire.MAX_REQUEST_BYTES)throw new AssertionError();
        if(ConfigurationWire.requestBytes(Map.of("example:k","x".repeat(4096),"example:y","x".repeat(4096)))<=ConfigurationWire.MAX_REQUEST_BYTES)throw new AssertionError();
        reject(()->ConfigurationWire.requestBytes(Map.of("example:k","x".repeat(4097))));
        var denied=new Result(false,0,"",List.of());inbox.begin(4);inbox.accept(ConfigurationWire.frames(4,denied).get(0));eq(inbox.take(4),denied);
        var ack=new Result(true,9,"",List.of());inbox.begin(5);inbox.accept(ConfigurationWire.frames(5,ack).get(0));eq(inbox.take(5),ack);
        System.out.println("Configuration wire contracts passed");
    }
}
'''
class ConfigurationWireTests(unittest.TestCase):
    def test_real_codec_and_bounded_reassembly(self):
        with tempfile.TemporaryDirectory() as tmp:
            work=Path(tmp);probe=work/'Probe.java';probe.write_text(PROBE,encoding='utf-8')
            for args in (['javac','--release','17','-d',str(work),str(JAVA/'api/v2/config/ConfigDefinition.java'),str(JAVA/'lib/net/ConfigurationWire.java'),str(probe)],['java','-Xmx128m','-cp',str(work),'Probe']):
                result=subprocess.run(args,cwd=work,text=True,capture_output=True,timeout=60)
                self.assertEqual(result.returncode,0,result.stdout+result.stderr)
            self.assertIn('Configuration wire contracts passed',result.stdout)

if __name__=='__main__':unittest.main()
