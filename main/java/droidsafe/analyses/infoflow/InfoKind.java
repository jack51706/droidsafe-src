package droidsafe.analyses.infoflow;

import java.util.HashMap;


/**
 * High Level information kind identified by a string type (name).
 * 
 * @author mgordon
 *
 */
public  class InfoKind implements InfoValue {
    /** name of this information kind */
    private String name;
    
    /** map of strings to the info kind that represents them */
    private static HashMap<String,InfoKind> infoKinds = new HashMap<String,InfoKind>();

    /** 
     * Given a string return (or create and return) the InfoKind object that
     * represents it.
     */
    public static InfoKind getInfoKind(String str) {
        if (!infoKinds.containsKey(str)) {
            infoKinds.put(str, new InfoKind(str));
        }
        
        return infoKinds.get(str);
    }
    
    /**
     * Create a new information kind named str.
     */
    private InfoKind(String str) {
        this.name = str;
    }

    /**
     * Return the string name of this information kind.
     */
    public String toString() {
        return name;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((name == null) ? 0 : name.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null) return false;
        if (getClass() != obj.getClass()) return false;
        InfoKind other = (InfoKind) obj;
        if (name == null) {
            if (other.name != null) return false;
        } else if (!name.equals(other.name)) return false;
        return true;
    }
}
