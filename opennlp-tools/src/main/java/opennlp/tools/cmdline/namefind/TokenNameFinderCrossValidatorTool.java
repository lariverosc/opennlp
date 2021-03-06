/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package opennlp.tools.cmdline.namefind;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import opennlp.tools.cmdline.AbstractCrossValidatorTool;
import opennlp.tools.cmdline.CmdLineUtil;
import opennlp.tools.cmdline.TerminateToolException;
import opennlp.tools.cmdline.namefind.TokenNameFinderCrossValidatorTool.CVToolParams;
import opennlp.tools.cmdline.params.CVParams;
import opennlp.tools.cmdline.params.DetailedFMeasureEvaluatorParams;
import opennlp.tools.namefind.BilouCodec;
import opennlp.tools.namefind.BioCodec;
import opennlp.tools.namefind.NameSample;
import opennlp.tools.namefind.NameSampleTypeFilter;
import opennlp.tools.namefind.TokenNameFinderCrossValidator;
import opennlp.tools.namefind.TokenNameFinderEvaluationMonitor;
import opennlp.tools.namefind.TokenNameFinderFactory;
import opennlp.tools.namefind.TokenNameFinderModel;
import opennlp.tools.util.InvalidFormatException;
import opennlp.tools.util.SequenceCodec;
import opennlp.tools.util.eval.EvaluationMonitor;
import opennlp.tools.util.model.ModelUtil;

public final class TokenNameFinderCrossValidatorTool
    extends AbstractCrossValidatorTool<NameSample, CVToolParams> {
  
  interface CVToolParams extends TrainingParams, CVParams, DetailedFMeasureEvaluatorParams {
  }

  public TokenNameFinderCrossValidatorTool() {
    super(NameSample.class, CVToolParams.class);
  }

  public String getShortDescription() {
    return "K-fold cross validator for the learnable Name Finder";
  }

  public void run(String format, String[] args) {
    super.run(format, args);

    mlParams = CmdLineUtil.loadTrainingParameters(params.getParams(), true);
    if (mlParams == null) {
      mlParams = ModelUtil.createDefaultTrainingParameters();
    }

    byte featureGeneratorBytes[] =
        TokenNameFinderTrainerTool.openFeatureGeneratorBytes(params.getFeaturegen());

    Map<String, Object> resources =
        TokenNameFinderTrainerTool.loadResources(params.getResources(), params.getFeaturegen());

    if (params.getNameTypes() != null) {
      String nameTypes[] = params.getNameTypes().split(",");
      sampleStream = new NameSampleTypeFilter(nameTypes, sampleStream);
    }
    
    List<EvaluationMonitor<NameSample>> listeners = new LinkedList<EvaluationMonitor<NameSample>>();
    if (params.getMisclassified()) {
      listeners.add(new NameEvaluationErrorListener());
    }
    TokenNameFinderDetailedFMeasureListener detailedFListener = null;
    if (params.getDetailedF()) {
      detailedFListener = new TokenNameFinderDetailedFMeasureListener();
      listeners.add(detailedFListener);
    }

    String sequenceCodecImplName = params.getSequenceCodec();
    
    if ("BIO".equals(sequenceCodecImplName)) {
      sequenceCodecImplName = BioCodec.class.getName();
    }
    else if ("BILOU".equals(sequenceCodecImplName)) {
      sequenceCodecImplName = BilouCodec.class.getName();
    }
    
    SequenceCodec<String> sequenceCodec = TokenNameFinderFactory.instantiateSequenceCodec(sequenceCodecImplName);
    
    TokenNameFinderFactory nameFinderFactory = null;
    try {
      nameFinderFactory = TokenNameFinderFactory.create(params.getFactory(),
          featureGeneratorBytes, resources, sequenceCodec);
    } catch (InvalidFormatException e) {
      throw new TerminateToolException(-1, e.getMessage(), e);
    }
    
    TokenNameFinderCrossValidator validator;
    try {
      validator = new TokenNameFinderCrossValidator(params.getLang(),
          params.getType(), mlParams, nameFinderFactory,
          listeners.toArray(new TokenNameFinderEvaluationMonitor[listeners.size()]));
      validator.evaluate(sampleStream, params.getFolds());
    } catch (IOException e) {
      throw new TerminateToolException(-1, "IO error while reading training data or indexing data: "
          + e.getMessage(), e);
    } finally {
      try {
        sampleStream.close();
      } catch (IOException e) {
        // sorry that this can fail
      }
    }

    System.out.println("done");

    System.out.println();
    
    if(detailedFListener == null) {
      System.out.println(validator.getFMeasure());
    } else {
      System.out.println(detailedFListener.toString());
    }
  }
}
