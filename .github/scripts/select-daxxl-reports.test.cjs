const assert = require('node:assert/strict');
const test = require('node:test');
const selectReports = require('./select-daxxl-reports.cjs');

function api(artifacts, overrides = {}) {
  return {
    rest: { actions: {
      listWorkflowRuns: async params => {
        assert.equal(params.workflow_id, 'daily-modpack-build.yml');
        assert.equal(params.branch, 'master');
        assert.equal(params.status, 'success');
        assert.equal(params.per_page, 100);
        return { data: { workflow_runs: [{
          id: 123, conclusion: 'success', head_branch: 'master', event: 'schedule',
          path: '.github/workflows/daily-modpack-build.yml',
          head_repository: { full_name: 'GTNewHorizons/DreamAssemblerXXL' },
          head_sha: 'abc', created_at: '2026-09-25T08:55:50Z', ...overrides,
        }] } };
      },
      listWorkflowRunArtifacts: 'artifacts',
    } },
    paginate: async (endpoint, params) => {
      assert.equal(endpoint, 'artifacts');
      assert.equal(params.run_id, 123);
      return artifacts;
    },
  };
}

const artifacts = [8, 17].map(target => ({
  id: target, name: `jvmdg-usage-daily-java${target}`, expired: false, digest: 'sha256:test',
}));

test('selects both report IDs from the same successful daily run', async () => {
  const source = await selectReports(api(artifacts));
  assert.equal(source.run_id, 123);
  assert.equal(source.head_sha, 'abc');
  assert.deepEqual(source.reports.map(report => report.id), [8, 17]);
});

test('selects the newest run when the API returns stale ordering', async () => {
  const github = api(artifacts);
  const listWorkflowRuns = github.rest.actions.listWorkflowRuns;
  github.rest.actions.listWorkflowRuns = async params => {
    const { data } = await listWorkflowRuns(params);
    return { data: { workflow_runs: [
      { ...data.workflow_runs[0], id: 122, created_at: '2026-09-14T11:00:16Z' },
      data.workflow_runs[0],
    ] } };
  };
  assert.equal((await selectReports(github)).run_id, 123);
});

test('fails on missing, expired or ambiguous reports without falling back', async () => {
  for (const invalid of [[], artifacts.slice(0, 1), [...artifacts, artifacts[0]],
    artifacts.map(artifact => ({ ...artifact, expired: true }))]) {
    await assert.rejects(selectReports(api(invalid)), /one unexpired/);
  }
});

test('rejects an unsuccessful run, another branch or a fork', async () => {
  for (const invalid of [{ conclusion: 'failure' }, { head_branch: 'feature' },
    { event: 'pull_request' }, { path: '.github/workflows/other.yml' },
    { head_repository: { full_name: 'someone/DreamAssemblerXXL' } }]) {
    await assert.rejects(selectReports(api(artifacts, invalid)), /Expected a successful daily/);
  }
});

test('an explicit run ID bypasses latest selection but retains validation', async () => {
  const github = api(artifacts);
  const { data } = await github.rest.actions.listWorkflowRuns({
    workflow_id: 'daily-modpack-build.yml', branch: 'master', status: 'success', per_page: 100,
  });
  github.rest.actions.listWorkflowRuns = async () => { throw new Error('Must not select latest'); };
  github.rest.actions.getWorkflowRun = async params => {
    assert.equal(params.run_id, '123');
    return { data: data.workflow_runs[0] };
  };
  assert.equal((await selectReports(github, '123')).run_id, 123);
  await assert.rejects(selectReports(github, '../123'), /positive integer/);
  data.workflow_runs[0].conclusion = 'failure';
  await assert.rejects(selectReports(github, '123'), /Expected a successful daily/);
});

test('fails when no successful daily exists', async () => {
  const github = api(artifacts);
  github.rest.actions.listWorkflowRuns = async () => ({ data: { workflow_runs: [] } });
  await assert.rejects(selectReports(github), /Expected a successful daily/);
});
