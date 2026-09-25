const owner = 'GTNewHorizons';
const repo = 'DreamAssemblerXXL';

module.exports = async function selectReports(github, runId = '') {
  let run;
  if (runId) {
    if (!/^[1-9][0-9]*$/.test(runId)) throw new Error('DAXXL run ID must be a positive integer');
    ({ data: run } = await github.rest.actions.getWorkflowRun({ owner, repo, run_id: runId }));
  } else {
    const { data } = await github.rest.actions.listWorkflowRuns({
      owner, repo, workflow_id: 'daily-modpack-build.yml',
      branch: 'master', status: 'success', exclude_pull_requests: true, per_page: 1,
    });
    run = data.workflow_runs[0];
  }
  if (!run || run.conclusion !== 'success' || run.head_branch !== 'master'
      || run.path !== '.github/workflows/daily-modpack-build.yml'
      || run.event === 'pull_request' || run.head_repository?.full_name !== `${owner}/${repo}`) {
    throw new Error('Expected a successful daily run from DAXXL master');
  }
  const artifacts = await github.paginate(github.rest.actions.listWorkflowRunArtifacts, {
    owner, repo, run_id: run.id, per_page: 100,
  });
  const reports = [8, 17].map(target => {
    const name = `jvmdg-usage-daily-java${target}`;
    const matches = artifacts.filter(artifact => artifact.name === name);
    if (matches.length !== 1 || matches[0].expired) {
      throw new Error(`Daily run ${run.id} must contain one unexpired ${name} artifact`);
    }
    const { id, digest } = matches[0];
    return { target, name, id, digest };
  });
  return {
    repository: `${owner}/${repo}`, run_id: run.id, run_attempt: run.run_attempt,
    head_sha: run.head_sha, url: run.html_url, created_at: run.created_at, reports,
  };
};
