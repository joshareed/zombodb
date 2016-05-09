package com.tcdi.zombodb.query_parser;

import org.elasticsearch.action.ActionFuture;
import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.client.Client;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.search.aggregations.bucket.terms.Terms;
import org.elasticsearch.search.aggregations.bucket.terms.TermsBuilder;

import java.util.*;

import static org.elasticsearch.index.query.QueryBuilders.boolQuery;
import static org.elasticsearch.index.query.QueryBuilders.constantScoreQuery;

public class ExpansionOptimizer {
    private final QueryRewriter rewriter;
    private final ASTQueryTree tree;
    private final IndexMetadataManager metadataManager;
    private final Client client;
    private final String searchPreference;
    private final boolean allowSingleIndex;
    private final boolean doFullFieldDataLookup;

    private Stack<ASTExpansion> generatedExpansionsStack = new Stack<>();

    public ExpansionOptimizer(QueryRewriter rewriter, ASTQueryTree tree, IndexMetadataManager metadataManager, Client client, String searchPreference, boolean allowSingleIndex, boolean doFullFieldDataLookup) {
        this.rewriter = rewriter;
        this.tree = tree;
        this.metadataManager = metadataManager;
        this.client = client;
        this.searchPreference = searchPreference;
        this.allowSingleIndex = allowSingleIndex;
        this.doFullFieldDataLookup = doFullFieldDataLookup;
    }

    public void optimize() {
        Collection<ASTExpansion> expansions = tree.getChildrenOfType(ASTExpansion.class);

        for (ASTExpansion expansion : expansions) {
            if (expansion.isGenerated())
                generatedExpansionsStack.push(expansion);
            try {
                expand(expansion, expansion.getIndexLink());
            } finally{
                if (expansion.isGenerated())
                    generatedExpansionsStack.pop();
            }
        }

        mergeAdjacentANDs(tree);
        mergeAdjacentORs(tree);
        pullUpNOTs(tree);

        flatten();
    }

    private void flatten() {
        QueryParserNode queryNode = tree.getQueryNode();
        if (queryNode instanceof ASTAnd || queryNode instanceof ASTOr) {
            if (queryNode.jjtGetNumChildren() == 1)
                ((QueryParserNode) queryNode.parent).replaceChild(queryNode, queryNode.getChild(0));
        }
    }

    private void expand(final ASTExpansion root, final ASTIndexLink link) {
        Stack<ASTExpansion> stack = buildExpansionStack(root, new Stack<ASTExpansion>());

        ASTIndexLink myIndex = metadataManager.getMyIndex();
        ASTIndexLink targetIndex = !generatedExpansionsStack.isEmpty() ? root.getIndexLink() : myIndex;
        QueryParserNode last = null;

        if (link.getFieldname() != null)
            IndexLinkOptimizer.stripPath(root, link.getFieldname());

        try {
            while (!stack.isEmpty()) {
                ASTExpansion expansion = stack.pop();
                String expansionFieldname = expansion.getFieldname();

                if (expansionFieldname == null)
                    expansionFieldname = expansion.getIndexLink().getRightFieldname();

                if (generatedExpansionsStack.isEmpty() && expansion.getIndexLink() == myIndex) {
                    last = expansion.getQuery();
                } else {
                    String leftFieldname = null;
                    String rightFieldname = null;

                    // before we try to resolve the expansion lets figure out if
                    // inverting it would produce less rows
                    expansion = maybeInvertExpansion(expansion);

                    if (expansion.isGenerated()) {

                        // at this point 'expansion' represents the set of records that match the #expand<>(...)'s subquery
                        // all of which are targeted towards the index that contains the #expand's <fieldname>

                        // the next step is to turn them into a set of 'expansionField' values
                        // then turn that around into a set of ids against myIndex, if the expansionField is not in myIndex
                        last = loadFielddata(expansion, expansion.getIndexLink().getLeftFieldname(), expansion.getIndexLink().getRightFieldname());

                        ASTIndexLink expansionSourceIndex = metadataManager.findField(expansionFieldname);
                        if (expansionSourceIndex != myIndex) {
                            // replace the ASTExpansion in the tree with the fieldData version
                            expansion.jjtAddChild(last, 1);

                            String targetPkey = myIndex.getRightFieldname();
                            String sourcePkey = metadataManager.getMetadata(expansion.getIndexLink().getIndexName()).getPrimaryKeyFieldName();

                            leftFieldname = targetPkey;
                            rightFieldname = sourcePkey;

                            last = loadFielddata(expansion, leftFieldname, rightFieldname);
                        }
                    } else {

                        List<String> path = metadataManager.calculatePath(targetIndex, expansion.getIndexLink());

                        int i = path.size()-1;
                        boolean oneToOne = i == 1;

                        while(i >= 0) {
                            final String rightIndex;

                            rightFieldname = path.get(i);
                            leftFieldname = path.get(--i);

                            if (!rightFieldname.contains(":")) {
                                // the right fieldname is a reference to a table not a specific field, so
                                // skip the path entry
                                continue;
                            }

                            rightIndex = rightFieldname.substring(0, rightFieldname.indexOf(':'));

                            leftFieldname = leftFieldname.substring(leftFieldname.indexOf(':') + 1);
                            rightFieldname = rightFieldname.substring(rightFieldname.indexOf(':') + 1);

                            if (last != null && !oneToOne) {
                                ASTIndexLink newLink = ASTIndexLink.create(leftFieldname, rightIndex, rightFieldname);
                                expansion.jjtAddChild(newLink, 0);
                                expansion.jjtAddChild(last, 1);
                            }

                            last = loadFielddata(expansion, leftFieldname, rightFieldname);

                            i--;
                        }

                        if (oneToOne && metadataManager.getUsedIndexes().size() == 1 && allowSingleIndex) {
                            last = expansion.getQuery();
                        } else if (last == null) {
                            last = loadFielddata(expansion, leftFieldname, rightFieldname);
                        }
                    }
                }

                // replace the ASTExpansion in the tree with the fieldData version
                ((QueryParserNode) expansion.parent).replaceChild(expansion, last);
            }
        } finally {
            metadataManager.setMyIndex(myIndex);
        }
    }

    private Stack<ASTExpansion> buildExpansionStack(QueryParserNode root, Stack<ASTExpansion> stack) {

        if (root != null) {
            if (root instanceof ASTExpansion) {
                stack.push((ASTExpansion) root);
                buildExpansionStack(((ASTExpansion) root).getQuery(), stack);
            } else {
                for (QueryParserNode child : root)
                    buildExpansionStack(child, stack);
            }
        }
        return stack;
    }

    private QueryParserNode loadFielddata(ASTExpansion node, String leftFieldname, String rightFieldname) {
        ASTIndexLink link = node.getIndexLink();
        QueryParserNode nodeQuery = node.getQuery();
        IndexMetadata nodeMetadata = metadataManager.getMetadata(link);
        IndexMetadata leftMetadata = metadataManager.getMetadataForField(leftFieldname);
        IndexMetadata rightMetadata = metadataManager.getMetadataForField(rightFieldname);
        boolean isPkey = nodeMetadata != null && leftMetadata != null && rightMetadata != null &&
                nodeMetadata.getPrimaryKeyFieldName().equals(nodeQuery.getFieldname()) && leftMetadata.getPrimaryKeyFieldName().equals(leftFieldname) && rightMetadata.getPrimaryKeyFieldName().equals(rightFieldname);

        if (nodeQuery instanceof ASTNotNull && isPkey) {
            // if the query is a "not null" query against a primary key field and is targeting a primary key field
            // we can just rewrite the query as a "not null" query against the leftFieldname
            // and avoid doing a search at all
            ASTNotNull notNull = new ASTNotNull(QueryParserTreeConstants.JJTNOTNULL);
            notNull.setFieldname(leftFieldname);
            return notNull;
        }

        TermsBuilder termsBuilder = new TermsBuilder(rightFieldname)
                .field(rightFieldname)
                .shardSize(!doFullFieldDataLookup ? 1024 : 0)
                .size(!doFullFieldDataLookup ? 1024 : 0);

        QueryBuilder query = constantScoreQuery(rewriter.applyExclusion(rewriter.build(nodeQuery), link.getIndexName()));
        QueryParserNode filterQuery = node.getFilterQuery();
        if (filterQuery != null) {
            BoolQueryBuilder bqb = boolQuery();
            bqb.must(query);
            bqb.must(rewriter.build(filterQuery));
            query = bqb;
        }

        SearchRequestBuilder builder = new SearchRequestBuilder(client)
                .setSize(0)
                .setSearchType(SearchType.COUNT)
                .setQuery(query)
                .setQueryCache(true)
                .setIndices(link.getIndexName())
                .setTrackScores(false)
                .setPreference(searchPreference)
                .addAggregation(termsBuilder);

        ActionFuture<SearchResponse> future = client.search(builder.request());

        try {
            SearchResponse response = future.get();
            final Terms agg = (Terms) response.getAggregations().iterator().next();

            ASTArray array = new ASTArray(QueryParserTreeConstants.JJTARRAY);
            array.setFieldname(leftFieldname);
            array.setOperator(QueryParserNode.Operator.EQ);
            array.setExternalValues(new Iterable<Object>() {
                @Override
                public Iterator<Object> iterator() {
                    final Iterator<Terms.Bucket> buckets = agg.getBuckets().iterator();
                    return new Iterator<Object>() {
                        @Override
                        public boolean hasNext() {
                            return buckets.hasNext();
                        }

                        @Override
                        public Object next() {
                            return buckets.next().getKey();
                        }

                        @Override
                        public void remove() {
                            buckets.remove();
                        }
                    };
                }
            }, agg.getBuckets().size());

            return array;
        } catch (Exception e) {
            throw new QueryRewriter.QueryRewriteException(e);
        }
    }

    private void mergeAdjacentANDs(QueryParserNode root) {
        if (root instanceof ASTAnd) {
            if (root.jjtGetNumChildren() > 1) {

                Map<String, Map<QueryParserNode, Set<Object>>> terms = new HashMap<>();

                buildNodeSets(root, terms);

                for(Map<QueryParserNode, Set<Object>> map : terms.values()) {
                    if (map.size() > 1) {
                        Set<Object> merged = null;
                        QueryParserNode first = null;
                        for (Map.Entry<QueryParserNode, Set<Object>> entry : map.entrySet()) {
                            QueryParserNode node = entry.getKey();
                            Set<Object> value = entry.getValue();

                            if (merged == null) {
                                merged = value;
                                first = node;
                            } else {
                                merged.retainAll(value);
                            }
                            root.removeNode(node);
                        }
                        assert(first != null);

                        ASTArray array = new ASTArray(QueryParserTreeConstants.JJTARRAY);
                        array.setFieldname(first.getFieldname());
                        array.setOperator(first.getOperator());
                        array.setExternalValues(merged, merged.size());

                        root.renumber();
                        root.jjtAddChild(array, root.jjtGetNumChildren());
                    }
                }

            }
        } else {
            for (QueryParserNode child : root)
                mergeAdjacentANDs(child);
        }
    }

    private void mergeAdjacentORs(QueryParserNode root) {
        if (root instanceof ASTOr) {
            if (root.jjtGetNumChildren() > 1) {

                Map<String, Map<QueryParserNode, Set<Object>>> terms = new HashMap<>();

                buildNodeSets(root, terms);

                for(Map<QueryParserNode, Set<Object>> map : terms.values()) {
                    if (map.size() > 1) {
                        Set<Object> merged = null;
                        QueryParserNode first = null;
                        for (Map.Entry<QueryParserNode, Set<Object>> entry : map.entrySet()) {
                            QueryParserNode node = entry.getKey();
                            Set<Object> value = entry.getValue();

                            if (merged == null) {
                                merged = value;
                                first = node;
                            } else {
                                merged.addAll(value);
                            }
                            root.removeNode(node);
                        }
                        assert(first != null);

                        ASTArray array = new ASTArray(QueryParserTreeConstants.JJTARRAY);
                        array.setFieldname(first.getFieldname());
                        array.setOperator(first.getOperator());
                        array.setExternalValues(merged, merged.size());

                        root.renumber();
                        root.jjtAddChild(array, root.jjtGetNumChildren());
                    }
                }

            }
        } else {
            for (QueryParserNode child : root)
                mergeAdjacentORs(child);
        }
    }

    private void pullUpNOTs(QueryParserNode root) {
        if (root instanceof ASTAnd) {
            Collection<ASTNot> nots = root.getChildrenOfType(ASTNot.class);

            if (nots.size() > 0) {
                Map<String, Map<QueryParserNode, Set<Object>>> terms = new HashMap<>();

                buildNodeSets(root, terms);

                for (ASTNot not : nots) {
                    QueryParserNode child = not.getChild(0);
                    if (child instanceof ASTArray) {
                        final ASTArray notArray = (ASTArray) child;
                        if (notArray.hasExternalValues()) {
                            String fieldname = child.getFieldname();
                            Map<QueryParserNode, Set<Object>> array = terms.get(fieldname);

                            if (array != null) {
                                for (Map.Entry<QueryParserNode, Set<Object>> entry : array.entrySet()) {
                                    QueryParserNode node = entry.getKey();
                                    if (node instanceof ASTArray) {
                                        Set<Object> values = entry.getValue();

                                        values.removeAll(new HashSet<>(new AbstractCollection<Object>() {
                                            @Override
                                            public Iterator<Object> iterator() {
                                                return notArray.getExternalValues().iterator();
                                            }

                                            @Override
                                            public int size() {
                                                return notArray.getTotalExternalValues();
                                            }
                                        }));

                                        ((ASTArray) node).setExternalValues(values, values.size());
                                        ((QueryParserNode) notArray.parent).removeNode(notArray);
                                        ((QueryParserNode) notArray.parent).renumber();
                                    }
                                }
                            }
                        }
                    }

                    if (not.jjtGetNumChildren() == 0) {
                        ((QueryParserNode) not.parent).removeNode(not);
                        ((QueryParserNode) not.parent).renumber();
                    }
                }
            }
        }

        for (QueryParserNode child : root)
            pullUpNOTs(child);
    }

    private void buildNodeSets(QueryParserNode root, Map<String, Map<QueryParserNode, Set<Object>>> terms) {
        for (QueryParserNode child : root) {
            Set<Object> set = new HashSet<>();

            IndexMetadata md = metadataManager.getMetadataForField(child.getFieldname());
            String pkey = md.getPrimaryKeyFieldName();
            if (pkey == null || !pkey.equals(child.getFieldname()))
                continue;   // can only do this for primary key fields

            if (!(child.getOperator() == QueryParserNode.Operator.CONTAINS || child.getOperator() == QueryParserNode.Operator.EQ))
                continue;

            boolean didWork = false;
            if (child instanceof ASTArray) {
                ASTArray array = (ASTArray) child;

                if (!array.isAnd()) {
                    Iterable<Object> itr = array.hasExternalValues() ? array.getExternalValues() : array.getChildValues();
                    for (Object obj : itr) {
                        set.add(String.valueOf(obj));
                    }
                    didWork = true;
                }
            } else if (child instanceof ASTNumber || child instanceof ASTWord) {
                set.add(String.valueOf(child.getValue()));
                didWork = true;
            }

            if (didWork) {
                Map<QueryParserNode, Set<Object>> map = terms.get(child.getFieldname());

                if (map == null)
                    terms.put(child.getFieldname(), map = new HashMap<>());

                map.put(child, set);
            }
        }
    }

    private ASTExpansion maybeInvertExpansion(ASTExpansion expansion) {
        long totalCnt, queryCnt;

        //
        // figure out how many records are in the index
        //
        totalCnt = estimateCount(expansion, false);

        //
        // then how many records this expansion is likely to return
        //
        queryCnt = estimateCount(expansion, true);

        if (queryCnt > totalCnt/2) {
            QueryParserNode expansionParent = (QueryParserNode) expansion.parent;

            //
            // and if the expansion is going to return more than 1/2 the database
            // invert it on the inner side of the expansion
            //
            ASTNot innerNot = new ASTNot(QueryParserTreeConstants.JJTNOT);
            innerNot.jjtAddChild(expansion.getQuery(), 0);
            expansion.jjtAddChild(innerNot, 1);

            //
            // and on the outer side.
            //
            // This way we're only shipping around the minimal number of rows
            // through the rest of the query
            //
            ASTNot outerNot = new ASTNot(QueryParserTreeConstants.JJTNOT);
            outerNot.jjtAddChild(expansion, 0);
            expansionParent.replaceChild(expansion, outerNot);
        }

        return expansion;
    }

    private long estimateCount(ASTExpansion expansion, boolean useQuery) {
        SearchRequestBuilder builder = new SearchRequestBuilder(client);
        builder.setIndices(expansion.getIndexLink().getIndexName());
        builder.setSize(0);
        builder.setSearchType(SearchType.COUNT);
        builder.setPreference(searchPreference);
        builder.setQueryCache(true);
        builder.setFetchSource(false);
        builder.setTrackScores(false);
        builder.setNoFields();
        if (useQuery)
            builder.setQuery(rewriter.build(expansion.getQuery()));

        try {
            return client.search(builder.request()).get().getHits().getTotalHits();
        } catch (Exception e) {
            throw new RuntimeException("Problem estimating count", e);
        }
    }
}
