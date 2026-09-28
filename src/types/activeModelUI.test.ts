import { getActiveModelUI, getModelDisplayInfo, ModelStatusLike } from './activeModelUI';

describe('ActiveModelUI', () => {
  const models: ModelStatusLike[] = [
    { model: 'gpt-4', activeConnections: 0, provider: 'openai', priority: 1 },
    { model: 'claude-3', activeConnections: 5, provider: 'anthropic', priority: 2 },
  ];

  it('should return active model info with provider and priority tags', () => {
    const result = getActiveModelUI(models);
    expect(result).not.toBeNull();
    if (result) {
      expect(result.main).toBe('claude-3');
      expect(result.provider).toBe('anthropic');
      expect(result.priority).toBe(2);
      expect(result.sub).toContain(result.provider);
      expect(result.sub).toContain('Priority');
    }
  });

  it('should fall back to the first model when none are active', () => {
    const result = getActiveModelUI([{ model: 'gpt-4', activeConnections: 0, provider: 'openai', priority: 1 }]);
    expect(result?.main).toBe('gpt-4');
    expect(result?.provider).toBe('openai');
  });

  it('should return null for an empty model list', () => {
    expect(getActiveModelUI([])).toBeNull();
  });

  it('should find display info by model id with provider and priority', () => {
    const result = getModelDisplayInfo(models, 'gpt-4');
    expect(result).not.toBeNull();
    if (result) {
      expect(result.main).toBe('gpt-4');
      expect(result.provider).toBe('openai');
      expect(result.priority).toBe(1);
      expect(result.sub).toContain(result.provider);
      expect(result.sub).toContain('Priority');
    }
  });

  it('should return null for an unknown model id', () => {
    expect(getModelDisplayInfo(models, 'nope')).toBeNull();
  });
});