import { ComponentFixture, TestBed } from '@angular/core/testing';

import { DetalheProfissional } from './detalhe-profissional';

describe('DetalheProfissional', () => {
  let component: DetalheProfissional;
  let fixture: ComponentFixture<DetalheProfissional>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [DetalheProfissional]
    })
    .compileComponents();

    fixture = TestBed.createComponent(DetalheProfissional);
    component = fixture.componentInstance;
    await fixture.whenStable();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
