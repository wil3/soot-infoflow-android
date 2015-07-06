package soot.jimple.infoflow.android.source;

import heros.InterproceduralCFG;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import soot.PrimType;
import soot.RefType;
import soot.SootClass;
import soot.SootField;
import soot.SootMethod;
import soot.Unit;
import soot.Value;
import soot.jimple.DefinitionStmt;
import soot.jimple.InstanceInvokeExpr;
import soot.jimple.Stmt;
import soot.jimple.infoflow.android.data.AndroidMethod;
import soot.jimple.infoflow.android.resources.LayoutControl;
import soot.jimple.infoflow.android.source.AndroidSourceSinkManager.LayoutMatchingMode;
import soot.jimple.infoflow.android.source.AndroidSourceSinkManager.SourceType;
import soot.jimple.infoflow.android.source.data.AccessPathTuple;
import soot.jimple.infoflow.android.source.data.SourceSinkDefinition;
import soot.jimple.infoflow.data.AccessPath;
import soot.jimple.infoflow.data.SootMethodAndClass;
import soot.jimple.infoflow.source.SourceInfo;
import soot.jimple.internal.JAssignStmt;
import soot.jimple.internal.JInstanceFieldRef;

public class SpecificAccessPathBasedSourceSinkManager extends AndroidSourceSinkManager{

	public SpecificAccessPathBasedSourceSinkManager(
			Set<SourceSinkDefinition> sources, Set<SourceSinkDefinition> sinks) {
		super(sources, sinks);
	}
	public SpecificAccessPathBasedSourceSinkManager(Set<SourceSinkDefinition> sources,
			Set<SourceSinkDefinition> sinks,
			Set<SootMethodAndClass> callbackMethods,
			LayoutMatchingMode layoutMatching,
			Map<Integer, LayoutControl> layoutControls) {
		super(sources, sinks, callbackMethods, layoutMatching, layoutControls);
	}
	private final Logger logger = LoggerFactory.getLogger(getClass());

	private String getLineNumberSignature(SootMethod sm, Stmt sCallSite){
		AndroidMethod am = new AndroidMethod(sCallSite.getInvokeExpr().getMethod());
		am.setLineNumber(sCallSite.getJavaSourceStartLineNumber());
		am.setDeclaredClass(sm.getDeclaringClass().getName());
		String signatureWithLineNumber = am.getSignature();
		return signatureWithLineNumber;
	}
	@Override
	public SourceInfo getSourceInfo(Stmt sCallSite, InterproceduralCFG<Unit, SootMethod> cfg) {
		// Callbacks and UI controls are already properly handled by our parent
		// implementation
		if (sCallSite.toString().contains("Auction: double price")){
			logger.trace("");
		}
		
		
		SourceSinkDefinition def = null;
		
		//First check and see if a field
		if (sCallSite instanceof JAssignStmt){
			Value value = ((JAssignStmt)sCallSite).getRightOp();
			if (value instanceof JInstanceFieldRef){
				JInstanceFieldRef ref = (JInstanceFieldRef) value;
				def = sourceMethods.get(ref.getFieldRef().toString());
				if (def != null){
					return new SourceInfo(new AccessPath(((JAssignStmt)sCallSite).getLeftOp(), true));
				}
			}
		}
		

		if (def == null){
			
	
			SourceType type = getSourceType(sCallSite, cfg);
			if (type == SourceType.NoSource)
				return null;
			if (type == SourceType.Callback || type == SourceType.UISource){
				SootMethod sm1 = cfg.getMethodOf(sCallSite);
				return null;//super.getSourceInfo(sCallSite, type);
			}
			
			
			// This is a method-based source, so we need to obtain the correct
			// access path
			final String signature = methodToSignature.getUnchecked(
					sCallSite.getInvokeExpr().getMethod());
			
			//First try more specific. We do this by 
			//create the signature of this callsite with the class and line number
			//If this fails to match we try more generically with out class and line number
			SootMethod sm = cfg.getMethodOf(sCallSite);
			
			if (!sm.getDeclaringClass().getPackageName().contains(appPackageName)){
				//logger.debug("Not including class {} because it is not in the app package {} ", sCallSite.toString(), appPackageName);
				//return null;
			}
		
			String signatureWithLineNumber = getLineNumberSignature(sm, sCallSite);
			SourceSinkDefinition specificDef = sourceMethods.get(signatureWithLineNumber);
			def = (null != specificDef) ? specificDef : sourceMethods.get(signature);
			
		}
		
		
		
		// If we don't have any more precise source information, we take the
		// default behavior of our parent implementation. We do the same of we
		// tried using access paths and failed, but this is a shortcut in case
		// we know that we don't have any access paths anyway.
		if (null == def || def.isEmpty())
			return super.getSourceInfo(sCallSite, cfg);
		

		
		// We have real access path definitions, so we can construct precise
		// source information objects
		Set<AccessPath> aps = new HashSet<>();
		
		// Check whether we need to taint the base object
		if (sCallSite.containsInvokeExpr()
				&& sCallSite.getInvokeExpr() instanceof InstanceInvokeExpr
				&& def.getBaseObjects() != null) {
			Value baseVal = ((InstanceInvokeExpr) sCallSite.getInvokeExpr()).getBase();
			for (AccessPathTuple apt : def.getBaseObjects())
				if (apt.isSource())
					aps.add(getAccessPathFromDef(baseVal, apt));
		}
		
		// Check whether we need to taint the return object
		if (sCallSite instanceof DefinitionStmt && def.getReturnValues() != null) {
			Value returnVal = ((DefinitionStmt) sCallSite).getLeftOp();
			for (AccessPathTuple apt : def.getReturnValues())
				if (apt.isSource())
					aps.add(getAccessPathFromDef(returnVal, apt));
		}
		
		// Check whether we need to taint parameters
		if (sCallSite.containsInvokeExpr()
				&& def.getParameters() != null
				&& def.getParameters().length > 0)
			for (int i = 0; i < sCallSite.getInvokeExpr().getArgCount(); i++)
				if (def.getParameters().length > i)
					for (AccessPathTuple apt : def.getParameters()[i])
						if (apt.isSource())
							aps.add(getAccessPathFromDef(sCallSite.getInvokeExpr().getArg(i), apt));
		
		// If we don't have any more precise source information, we take the
		// default behavior of our parent implementation
		if (aps.isEmpty())
			return super.getSourceInfo(sCallSite, cfg);
		
		return new SourceInfo(aps);
	}
	
	/**
	 * Creates an access path from an access path definition object
	 * @param baseVal The base for the new access path
	 * @param apt The definition from which to create the new access path
	 * @return The newly created access path
	 */
	protected AccessPath getAccessPathFromDef(Value baseVal, AccessPathTuple apt) {
		if (baseVal.getType() instanceof PrimType
				|| apt.getFields() == null
				|| apt.getFields().length == 0)
			return new AccessPath(baseVal, true);
		
		SootClass baseClass = ((RefType) baseVal.getType()).getSootClass();
		SootField[] fields = new SootField[apt.getFields().length];
		for (int i = 0; i < fields.length; i++)
			fields[i] = baseClass.getFieldByName(apt.getFields()[i]);
		
		return new AccessPath(baseVal, fields, true);
	}
	
	@Override
	public boolean isSink(Stmt sCallSite, InterproceduralCFG<Unit, SootMethod> cfg,
			AccessPath sourceAccessPath) {
		
		if (!sCallSite.containsInvokeExpr())
			return false;
				
		if (sCallSite.toString().contains("get(")){
			//logger.info("");
		}
		SourceSinkDefinition specificDef = null;
		try {
			SootMethod sm = cfg.getMethodOf(sCallSite);
			String signatureWithLineNumber = getLineNumberSignature(sm, sCallSite);
			specificDef = sinkMethods.get(signatureWithLineNumber);
			
			if (!sm.getDeclaringClass().getPackageName().contains(appPackageName)){
				//logger.debug("Not including class {} as sink because it is not in the app package {} ", sCallSite.toString(), appPackageName);
				//return false;
			}
			
			
		} catch(Exception e){}
		
		
		// Get the sink definition
		final String methodSignature = methodToSignature.getUnchecked(
				sCallSite.getInvokeExpr().getMethod());
		SourceSinkDefinition def = (null != specificDef) ? specificDef : sinkMethods.get(methodSignature);
		if (def == null)
			return false;
		
		// If we have no precise information, we conservatively assume that
		// everything is tainted without looking at the access path
		if (def.isEmpty())
			return true;
		
		// If we are only checking whether this statement can be a sink in
		// general, we know this by now
		if (sourceAccessPath == null)
			return true;
		
		// Check whether the base object matches our definition
		if (sCallSite.getInvokeExpr() instanceof InstanceInvokeExpr
				&& def.getBaseObjects() != null) {
			for (AccessPathTuple apt : def.getBaseObjects())
				if (apt.isSink() && accessPathMatches(sourceAccessPath, apt))
					return true;	
		}
		
		// Check whether a parameter matches our definition
		if (def.getParameters() != null && def.getParameters().length > 0) {
			// Get the tainted parameter index
			for (int i = 0; i < sCallSite.getInvokeExpr().getArgCount(); i++)
				if (sCallSite.getInvokeExpr().getArg(i) == sourceAccessPath.getPlainValue()) {
					// Check whether we have a sink on that parameter
					if (def.getParameters().length > i)
						for (AccessPathTuple apt : def.getParameters()[i])
							if (apt.isSink() && accessPathMatches(sourceAccessPath, apt))
								return true;
				}
		}
		
		// No matching access path found
		return false;
	}
	
	/**
	 * Checks whether the given access path matches the given definition
	 * @param sourceAccessPath The access path to check
	 * @param apt The definition against which to check the access path
	 * @return True if the given access path matches the given definition,
	 * otherwise false
	 */
	private boolean accessPathMatches(AccessPath sourceAccessPath,
			AccessPathTuple apt) {
		// If the source or sink definitions does not specify any fields, it
		// always matches
		if (apt.getFields() == null
				|| apt.getFields().length == 0
				|| sourceAccessPath == null)
			return true;
		
		for (int i = 0; i < apt.getFields().length; i++) {
			// If a.b.c.* is our defined sink and a.b is tainted, this is not a
			// leak. If a.b.* is tainted, it is. 
			if (i >= sourceAccessPath.getFieldCount())
				return sourceAccessPath.getTaintSubFields();
			
			// Compare the fields
			if (!sourceAccessPath.getFields()[i].getName().equals(apt.getFields()[i]))
				return false;
		}
		return true;
	}
	
	


}
