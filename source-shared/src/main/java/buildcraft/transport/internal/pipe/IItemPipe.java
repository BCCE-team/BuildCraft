package buildcraft.transport.internal.pipe;

/** Minimal item-pipe contract shared with code that cannot depend on transport implementations. */
public interface IItemPipe {
    int colorID = 0;
	
    PipeDefinition getDefinition();
    
    public default int getcolorID() {
    	return colorID;
    }
}
