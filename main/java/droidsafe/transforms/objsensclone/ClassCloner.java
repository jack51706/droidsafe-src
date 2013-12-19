package droidsafe.transforms.objsensclone;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import droidsafe.analyses.pta.PTABridge;
import droidsafe.analyses.strings.JSAStrings;
import droidsafe.android.app.Project;
import droidsafe.android.system.API;
import droidsafe.main.Config;
import droidsafe.speclang.Method;
import droidsafe.utils.SootMethodList;
import droidsafe.utils.SootUtils;
import soot.Body;
import soot.Modifier;
import soot.Scene;
import soot.SootClass;
import soot.SootField;
import soot.SootFieldRef;
import soot.SootMethod;
import soot.SootMethodRef;
import soot.Type;
import soot.Value;
import soot.ValueBox;
import soot.jimple.FieldRef;
import soot.jimple.InvokeExpr;
import soot.jimple.SpecialInvokeExpr;
import soot.jimple.Stmt;
import soot.jimple.StmtBody;
import soot.options.Options;
import soot.util.Chain;

/**
 * This transformation will create a clone of a given class that is appropriate for separating the cloned
 * class from the parent in the points to analysis, such that we can introduce Object Sensitivity for a subset of 
 * classes.
 * 
 * In the original class, all private fields are made protected, so that code in the clone can access them.
 * 
 * The clone that is created does not include fields from the ancestor classes.
 * 
 * All non-static methods from the original class plus its ancestors are added to the clone 
 * (they are added in a way such that method inheritance is correctly observed from the original hierarchy).  
 * 
 * @author mgordon
 *
 */
public class ClassCloner {
    /** static logger class */
    private static final Logger logger = LoggerFactory.getLogger(ClassCloner.class);
    /** original soot class which we are cloning */
    private SootClass original;
    /** the clone we have created */
    private SootClass clone;
    /** a list of ancestor of the original class, plus the original class */
    private Set<SootClass> ancestorsOfIncluding;;
    /** methods of the new cloned class */
    private SootMethodList methods;
    /** unique ID used for introduced fields */
    private static int uniqueID = 0;
    /** appended to name cloned classes */
    public static final String CLONE_POSTFIX = "_ds_clone_";
    /** set of methods from ancestors that we have cloned into this clone */
    private Set<SootMethod> ancestorMethodsAdded = new HashSet<SootMethod>();
    /** methods of the clone that are currently reachable based on if the orig method is reachable */
    private Set<SootMethod> reachableClonedMethods;


    /**
     * Private constructor for a specific class cloner.
     */
    private ClassCloner(SootClass org) {
        this.original = org;
        methods = new SootMethodList();
        ancestorsOfIncluding = new HashSet<SootClass>();
        reachableClonedMethods = new HashSet<SootMethod>();
    }

    /**
     * Return the cloned class.
     */
    public SootClass getClonedClass() {
        return clone;
    }


    /**
     * Return set of cloned methods that are reachable based on whether the original method 
     * that was clone was reachable based on the current pta result.
     * @return
     */
    public Set<SootMethod> getReachableClonedMethods() {
        return reachableClonedMethods;
    }

    /** 
     * Static call to clone a particular class, and return the clone. 
     * 
     * If isAPIClass is true, then treat the cloned class as an api class and add it to the list of api classes, and
     * set its methods as safe,spec,ban based on ancestors.
     */
    public static ClassCloner cloneClass(SootClass original) {
        ClassCloner c = new ClassCloner(original);
        c.cloneAndInstallClass();
        return c;
    }

    /**
     * Perform the work of actually clone class, changing fields and cloning methods.
     */
    private void cloneAndInstallClass() {
        clone = new SootClass(original.getName() + CLONE_POSTFIX + uniqueID, 
            original.getModifiers());
        uniqueID++;

        //System.out.printf("Cloning class %s with %s.\n", original, clone);

        //set parent
        if (original.isFinal()) {
            //change final modifier
            logger.info("Changing final modifier on {}", original);
            original.setModifiers(original.getModifiers() ^ Modifier.FINAL);
        }
        clone.setSuperclass(original);

        //modify ancestors fields
        SootClass ancestor = original;
        while (!"java.lang.Object".equals(ancestor.getName())) {
            makeAncestorFieldsVisible(ancestor);
            ancestorsOfIncluding.add(ancestor);
            ancestor = ancestor.getSuperclass();
        }

        //create the class methods
        ancestor = original;
        while (!"java.lang.Object".equals(ancestor.getName())) {
            incorporateAncestorMethods(ancestor);
            ancestor = ancestor.getSuperclass();
        }

        //install the class
        Scene.v().addClass(clone);
        Scene.v().loadClass(clone.getName(), SootClass.BODIES);
        clone.setApplicationClass();  

        if (API.v().isSystemClass(original)) {
            API.v().addSystemClass(clone);
        }

        if (API.v().isContainerClass(original.getName())) 
            API.v().addContainerClass(clone);

        if (Project.v().isSrcClass(original)) {
            Project.v().addSrcClass(clone);
        }

        if (Project.v().isGenClass(original)) {
            Project.v().addGenClass(clone);
        }

        if (Project.v().isLibClass(original)) {
            Project.v().addLibClass(clone);
        }

        fixInvokeSpecials();
    }

    private void fixInvokeSpecials() {
        for (SootMethod method : clone.getMethods()) {

            Body body = method.getActiveBody();
            StmtBody stmtBody = (StmtBody)body;
            Chain units = stmtBody.getUnits();
            Iterator stmtIt = units.iterator();

            while (stmtIt.hasNext()) {
                Stmt stmt = (Stmt)stmtIt.next();

                if (stmt.containsInvokeExpr() && stmt.getInvokeExpr() instanceof SpecialInvokeExpr) {
                    SpecialInvokeExpr si = (SpecialInvokeExpr) stmt.getInvokeExpr();

                    if (ancestorMethodsAdded.contains(si.getMethod())) {
                        SootMethodRef clonedMethodRef = clone.getMethod(si.getMethod().getSubSignature()).makeRef();
                        si.setMethodRef(clonedMethodRef);
                    }
                }
            }
        }
    }

    /**
     * Return true if the clone already contains a method that would resolve to this method, this 
     * is the test that mimics virtual dispatch, so we don't clone in methods that would not be called.
     */
    private boolean containsMethod(String signature) {
        //check this class for the method with polymorpism
        String mName = SootUtils.grabName(signature);
        String[] args = SootUtils.grabArgs(signature);
        String rtype = SootUtils.grabReturnType(signature);

        for (SootMethod curr : methods) {
            if (!curr.getName().equals(mName) || curr.getParameterCount() != args.length)
                continue;

            //check the return types
            Type returnType = SootUtils.toSootType(rtype);
            if (!SootUtils.isSubTypeOfIncluding(returnType, curr.getReturnType())) 
                continue;

            boolean foundIncompArg = false;            
            for (int i = 0; i < args.length; i++) {
                if (!SootUtils.isSubTypeOfIncluding(SootUtils.toSootType(args[i]), curr.getParameterType(i))) {
                    foundIncompArg = true;
                    continue;
                }
            }

            //at least one parameter does not match!
            if (foundIncompArg)
                continue;


            return true;
        }

        //didn't find it
        return false;
    }

    /**
     * Change private to protected for ancestor fields.
     */
    private void makeAncestorFieldsVisible(SootClass ancestor) {  
        for (SootField ancestorField : ancestor.getFields()) {
            if (ancestorField.isPrivate()) {
                //turn on protected
                ancestorField.setModifiers(ancestorField.getModifiers() | Modifier.PROTECTED);
                //turn off private
                ancestorField.setModifiers(ancestorField.getModifiers() ^ Modifier.PRIVATE);
            }

            //turn off final for ancestor methods
            if (ancestorField.isFinal())
                ancestorField.setModifiers(ancestorField.getModifiers() ^ Modifier.FINAL);
        }
    }

    /**
     * Clone non-static ancestor methods that are not hidden by virtual dispatch.
     */
    private void incorporateAncestorMethods(SootClass ancestor) {

        //create all methods, cloning body, replacing instance field refs
        for (SootMethod ancestorM : ancestor.getMethods()) {
            if (ancestorM.isAbstract() || ancestorM.isPhantom() || !ancestorM.isConcrete() || 
                    SootUtils.isRuntimeStubMethod(ancestorM))
                continue;

            //never clone static methods
            if (ancestorM.isStatic())
                continue;


            //check if this method already exists
            if (containsMethod(ancestorM.getSignature())) {
                //System.out.printf("\tAlready contains method %s.\n", ancestorM);
                continue;
            }

            //turn off final for ancestor methods
            if (ancestorM.isFinal())
                ancestorM.setModifiers(ancestorM.getModifiers() ^ Modifier.FINAL);

            ancestorMethodsAdded.add(ancestorM);

            SootMethod newMeth = new SootMethod(ancestorM.getName(), ancestorM.getParameterTypes(),
                ancestorM.getReturnType(), ancestorM.getModifiers(), ancestorM.getExceptions());

            //System.out.printf("\tAdding method %s.\n", ancestorM);
            //register method
            methods.addMethod(newMeth);
            clone.addMethod(newMeth);

            if (API.v().isSystemClass(ancestorM.getDeclaringClass())) {
                if (API.v().isBannedMethod(ancestorM.getSignature())) 
                    API.v().addBanMethod(newMeth);
                else if (API.v().isSpecMethod(ancestorM)) 
                    API.v().addSpecMethod(newMeth);
                else if (API.v().isSafeMethod(ancestorM)) 
                    API.v().addSafeMethod(newMeth);
                else {
                    //some methods are auto generated and don't have a classification 
                    //make them safe
                    API.v().addSafeMethod(newMeth);
                }
            } 

            //clone body
            Body newBody = (Body)ancestorM.retrieveActiveBody().clone();
            newMeth.setActiveBody(newBody);

            updateJSAResults(ancestorM.retrieveActiveBody(), newBody);
            
            //if the original method is reachable, then so is this method
            if (PTABridge.v().getAllReachableMethods().contains(ancestorM))
                reachableClonedMethods.add(newMeth);
        }
    }

    /**
     * For each value in original body that is hotspots for JSA, add the corresponding cloned
     * value in the clone body to the JSA results with the same result.
     */
    private void updateJSAResults(Body originalBody, Body cloneBody) {
        if (!Config.v().runStringAnalysis || !JSAStrings.v().hasRun())
            return;

        assert originalBody.getUnits().size() == cloneBody.getUnits().size();

        //loop over all methods of both clone and originals
        Iterator originalIt = originalBody.getUnits().iterator();
        Iterator cloneIt = cloneBody.getUnits().iterator();
        
        while (originalIt.hasNext()) {
            Stmt origStmt = (Stmt)originalIt.next();
            Stmt cloneStmt = (Stmt)cloneIt.next();
            
            if (!origStmt.containsInvokeExpr()) {
                continue;
            }
           
            InvokeExpr origInvokeExpr = (InvokeExpr)origStmt.getInvokeExpr();
            InvokeExpr cloneInvokeExpr = (InvokeExpr)cloneStmt.getInvokeExpr();
            
            //iterate over the args and see if any arg from orig is tracked by jsa
            //if so, add the clone to jsa results
            for (int i = 0; i < origInvokeExpr.getArgCount(); i++) {
                ValueBox origVB = origInvokeExpr.getArgBox(i);
                
                if (JSAStrings.v().isHotspotValue(origVB.getValue())) {
                    ValueBox cloneVB = cloneInvokeExpr.getArgBox(i);
                    JSAStrings.v().copyResult(origVB.getValue(), 
                        cloneInvokeExpr.getMethodRef().getSignature(), 
                        i, 
                        cloneVB);
                }
            }
        }   
    }

    public static String removeClassCloneSuffix(String str) {
        String regex = CLONE_POSTFIX+"[0-9]+";
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(str);
        return matcher.replaceAll("");
    }

    public static SootClass getClonedClassFromClone(SootClass clone) {
        return Scene.v().getSootClass(ClassCloner.removeClassCloneSuffix(clone.getName()));
    }
}
