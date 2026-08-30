from pathlib import Path


def replace_once(text, old, new, label):
    if old not in text:
        raise SystemExit(f"missing expected source block: {label}")
    if text.count(old) != 1:
        raise SystemExit(f"expected exactly one source block for {label}, found {text.count(old)}")
    return text.replace(old, new, 1)


doc_path = Path("app/src/main/java/ir/chobyar/sketch/core/SketchDocument.java")
doc = doc_path.read_text()
doc = replace_once(
    doc,
    """        for (String id : selection) {\n            SketchEntity current = moved.get(id);\n            if (current == null) continue;\n            SketchEntity candidate = current.translated(dxMm, dyMm);\n""",
    """        for (String id : selection) {\n            SketchEntity current = moved.get(id);\n            if (current == null || isFixedEntity(id)) continue;\n            SketchEntity candidate = current.translated(dxMm, dyMm);\n""",
    "skip FIXED entities during translation",
)
doc = replace_once(
    doc,
    """    private static LinkedHashMap<String, SketchEntity> requireSolvedSnapshot(\n""",
    """    private boolean isFixedEntity(String entityId) {\n        for (SketchConstraint constraint : constraints.values()) {\n            if (constraint.kind == SketchConstraint.Kind.FIXED\n                    && constraint.primaryEntityId.equals(entityId)) return true;\n        }\n        return false;\n    }\n\n    private static LinkedHashMap<String, SketchEntity> requireSolvedSnapshot(\n""",
    "model-owned FIXED lookup",
)
doc_path.write_text(doc)

solver_path = Path("app/src/main/java/ir/chobyar/sketch/core/DeterministicSketchConstraintSolver.java")
solver = solver_path.read_text()
solver = replace_once(
    solver,
    """        LinkedHashMap<String, SketchEntity> entities = copyEntities(sourceEntities);\n        List<SketchConstraint> constraints = copyConstraints(sourceConstraints);\n\n        for (SketchConstraint constraint : constraints) {\n""",
    """        LinkedHashMap<String, SketchEntity> entities = copyEntities(sourceEntities);\n        List<SketchConstraint> constraints = copyConstraints(sourceConstraints);\n        LinkedHashMap<String, SketchEntity> fixedEntities = fixedEntitySnapshots(entities, constraints);\n\n        for (SketchConstraint constraint : constraints) {\n""",
    "capture FIXED source geometry",
)
solver = replace_once(
    solver,
    """        for (int iteration = 1; iteration <= MAX_ITERATIONS; iteration++) {\n            for (SketchConstraint constraint : constraints) apply(constraint, entities);\n            residual = maxResidual(constraints, entities);\n""",
    """        for (int iteration = 1; iteration <= MAX_ITERATIONS; iteration++) {\n            for (SketchConstraint constraint : constraints) apply(constraint, entities);\n            restoreFixedEntities(entities, fixedEntities);\n            residual = maxResidual(constraints, entities);\n""",
    "restore FIXED geometry before residual",
)
solver = replace_once(
    solver,
    """    private static String unsupportedReason(SketchConstraint c, Map<String, SketchEntity> entities) {\n""",
    """    private static LinkedHashMap<String, SketchEntity> fixedEntitySnapshots(\n            Map<String, SketchEntity> entities, List<SketchConstraint> constraints) {\n        LinkedHashMap<String, SketchEntity> fixed = new LinkedHashMap<>();\n        for (SketchConstraint constraint : constraints) {\n            if (constraint.kind != SketchConstraint.Kind.FIXED) continue;\n            SketchEntity entity = entities.get(constraint.primaryEntityId);\n            if (entity != null) fixed.put(constraint.primaryEntityId, entity.copy());\n        }\n        return fixed;\n    }\n\n    private static void restoreFixedEntities(Map<String, SketchEntity> entities,\n                                             Map<String, SketchEntity> fixedEntities) {\n        for (Map.Entry<String, SketchEntity> entry : fixedEntities.entrySet()) {\n            entities.put(entry.getKey(), entry.getValue().copy());\n        }\n    }\n\n    private static String unsupportedReason(SketchConstraint c, Map<String, SketchEntity> entities) {\n""",
    "FIXED snapshot helpers",
)
solver = replace_once(
    solver,
    """            case POINT_ON_ENTITY:\n                if (!(a instanceof SketchGeometry.Line) || !isPointHost(b)) {\n                    return \"POINT_ON_ENTITY requires a line endpoint and line/circle/arc host\";\n                }\n                if (b instanceof SketchGeometry.Line && isDegenerateLine((SketchGeometry.Line) b)) {\n                    return \"POINT_ON_ENTITY requires a non-degenerate line host\";\n                }\n                return validEndpoint(c.primaryPointIndex)\n                        ? null : \"POINT_ON_ENTITY requires endpoint index 0 or 1\";\n            default:\n""",
    """            case POINT_ON_ENTITY:\n                if (!(a instanceof SketchGeometry.Line) || !isPointHost(b)) {\n                    return \"POINT_ON_ENTITY requires a line endpoint and line/circle/arc host\";\n                }\n                if (b instanceof SketchGeometry.Line && isDegenerateLine((SketchGeometry.Line) b)) {\n                    return \"POINT_ON_ENTITY requires a non-degenerate line host\";\n                }\n                return validEndpoint(c.primaryPointIndex)\n                        ? null : \"POINT_ON_ENTITY requires endpoint index 0 or 1\";\n            case FIXED:\n                return null;\n            default:\n""",
    "support FIXED validation",
)
solver = replace_once(
    solver,
    """            case POINT_ON_ENTITY: {\n                SketchGeometry.Line owner = line(entities, c.primaryEntityId);\n                SketchEntity host = entities.get(c.secondaryEntityId);\n                SketchGeometry.Point p = endpoint(owner, c.primaryPointIndex);\n                entities.put(c.primaryEntityId,\n                        withEndpoint(owner, c.primaryPointIndex, projectToEntity(host, p)));\n                break;\n            }\n            default:\n""",
    """            case POINT_ON_ENTITY: {\n                SketchGeometry.Line owner = line(entities, c.primaryEntityId);\n                SketchEntity host = entities.get(c.secondaryEntityId);\n                SketchGeometry.Point p = endpoint(owner, c.primaryPointIndex);\n                entities.put(c.primaryEntityId,\n                        withEndpoint(owner, c.primaryPointIndex, projectToEntity(host, p)));\n                break;\n            }\n            case FIXED:\n                break;\n            default:\n""",
    "FIXED apply no-op",
)
solver = replace_once(
    solver,
    """    private static double residual(SketchConstraint c, Map<String, SketchEntity> entities) {\n        SketchGeometry.Line a = line(entities, c.primaryEntityId);\n""",
    """    private static double residual(SketchConstraint c, Map<String, SketchEntity> entities) {\n        if (c.kind == SketchConstraint.Kind.FIXED) return 0.0;\n        SketchGeometry.Line a = line(entities, c.primaryEntityId);\n""",
    "FIXED residual without line cast",
)
solver_path.write_text(solver)

print("Applied K3.7 whole-entity FIXED semantics")
